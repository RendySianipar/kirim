package dev.rendy.kirim.transfer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.account.LedgerRepository
import dev.rendy.kirim.outbox.OutboxRepository
import dev.rendy.kirim.outbox.OutboxStatus
import dev.rendy.kirim.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

class TransferApiIntegrationTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var transfers: TransferRepository
    @Autowired lateinit var outbox: OutboxRepository

    private fun body(from: UUID, to: UUID, amount: Long, description: String? = null) =
        objectMapper.writeValueAsString(mapOf("fromAccountId" to from, "toAccountId" to to, "amount" to amount, "description" to description))

    private fun postTransfer(key: String?, json: String) = mockMvc.post("/api/v1/transfers") {
        contentType = MediaType.APPLICATION_JSON
        if (key != null) header("Idempotency-Key", key)
        content = json
    }

    @Test
    fun `completes a transfer, moves money once and records the ledger and outbox`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)

        val result = postTransfer("key-1", body(alice.id, bob.id, 40_000, "lunch"))
            .andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("COMPLETED") }
                jsonPath("$.amount") { value(40_000) }
            }
            .andReturn()

        val transferId = UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
        assertEquals(60_000L, accounts.get(alice.id).balance)
        assertEquals(40_000L, accounts.get(bob.id).balance)
        assertEquals(accounts.get(alice.id).balance, ledger.netAmount(alice.id))
        assertEquals(accounts.get(bob.id).balance, ledger.netAmount(bob.id))

        val events = outbox.findByAggregate(transferId)
        assertEquals(1, events.size)
        assertEquals("TRANSFER_COMPLETED", events.single().eventType)
        assertEquals(OutboxStatus.PENDING, events.single().status)
    }

    @Test
    fun `retrying with the same key replays the original result without moving money again`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)
        val json = body(alice.id, bob.id, 25_000)

        val first = postTransfer("retry-key", json).andExpect { status { isCreated() } }.andReturn()
        val second = postTransfer("retry-key", json)
            .andExpect {
                status { isOk() }
                header { string("Idempotent-Replayed", "true") }
            }
            .andReturn()

        val firstId = objectMapper.readTree(first.response.contentAsString)["id"].asText()
        val secondId = objectMapper.readTree(second.response.contentAsString)["id"].asText()
        assertEquals(firstId, secondId)
        assertEquals(75_000L, accounts.get(alice.id).balance)
        assertEquals(1L, transfers.count())
    }

    @Test
    fun `reusing a key with a different body is rejected with 422`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)

        postTransfer("same-key", body(alice.id, bob.id, 10_000)).andExpect { status { isCreated() } }
        postTransfer("same-key", body(alice.id, bob.id, 99_000))
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("IDEMPOTENCY_KEY_REUSED") }
            }

        assertEquals(90_000L, accounts.get(alice.id).balance)
    }

    @Test
    fun `insufficient funds produces a REJECTED transfer and leaves balances untouched`() {
        val alice = accounts.open("Alice", "IDR", 5_000)
        val bob = accounts.open("Bob", "IDR", 0)

        postTransfer("poor-key", body(alice.id, bob.id, 10_000))
            .andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("REJECTED") }
                jsonPath("$.failureReason") { value("INSUFFICIENT_FUNDS") }
            }

        assertEquals(5_000L, accounts.get(alice.id).balance)
        assertEquals(0L, accounts.get(bob.id).balance)
    }

    @Test
    fun `missing Idempotency-Key header is a 400`() {
        val alice = accounts.open("Alice", "IDR", 5_000)
        val bob = accounts.open("Bob", "IDR", 0)

        postTransfer(null, body(alice.id, bob.id, 1_000)).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `invalid amount is a 400 problem detail`() {
        val alice = accounts.open("Alice", "IDR", 5_000)
        val bob = accounts.open("Bob", "IDR", 0)

        postTransfer("zero-key", body(alice.id, bob.id, 0))
            .andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            }
    }

    @Test
    fun `unknown account is a 404 and does not burn the idempotency key`() {
        val alice = accounts.open("Alice", "IDR", 5_000)
        val ghost = UUID.randomUUID()

        postTransfer("ghost-key", body(alice.id, ghost, 1_000))
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
            }

        // The failed attempt rolled back, so the same key works once the request is fixed.
        val bob = accounts.open("Bob", "IDR", 0)
        postTransfer("ghost-key", body(alice.id, bob.id, 1_000)).andExpect { status { isCreated() } }
    }

    @Test
    fun `account statement lists ledger entries newest first`() {
        val alice = accounts.open("Alice", "IDR", 50_000)
        val bob = accounts.open("Bob", "IDR", 0)
        postTransfer("stmt-key", body(alice.id, bob.id, 20_000)).andExpect { status { isCreated() } }

        mockMvc.get("/api/v1/accounts/${alice.id}/entries")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].type") { value("DEBIT") }
                jsonPath("$[0].balanceAfter") { value(30_000) }
                jsonPath("$[1].type") { value("CREDIT") }
            }
    }
}
