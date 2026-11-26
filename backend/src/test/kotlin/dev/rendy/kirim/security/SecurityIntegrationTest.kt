package dev.rendy.kirim.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.support.Auth
import dev.rendy.kirim.support.IntegrationTest
import dev.rendy.kirim.transfer.TransferCommand
import dev.rendy.kirim.transfer.TransferService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Who may do what. Tokens are simulated with spring-security-test (no Keycloak
 * needed); the subjects stand for Keycloak user ids.
 */
class SecurityIntegrationTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var transfers: TransferService

    private val alice = Auth.customer(ALICE)
    private val bob = Auth.customer(BOB)

    private fun postTransfer(user: RequestPostProcessor?, from: UUID, to: UUID, amount: Long) =
        mockMvc.post("/api/v1/transfers") {
            if (user != null) with(user)
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", UUID.randomUUID().toString())
            content = objectMapper.writeValueAsString(mapOf("fromAccountId" to from, "toAccountId" to to, "amount" to amount))
        }

    @Test
    fun `no token is a 401 problem with WWW-Authenticate`() {
        mockMvc.get("/api/v1/accounts").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `health and API docs stay public`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
    }

    @Test
    fun `a token without a Kirim role is a 403`() {
        mockMvc.get("/api/v1/accounts") { with(Auth.noRole()) }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `a new account belongs to the caller, whatever the body says`() {
        mockMvc.post("/api/v1/accounts") {
            with(alice)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ownerName":"Alice","openingBalance":1000,"ownerId":"someone-else"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.ownerId") { value(ALICE) }
        }
    }

    @Test
    fun `customers list only their own accounts, OPS lists all`() {
        accounts.open("Alice", "IDR", 1_000, ownerId = ALICE)
        accounts.open("Bob", "IDR", 1_000, ownerId = BOB)

        mockMvc.get("/api/v1/accounts") { with(alice) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].ownerId") { value(ALICE) }
        }
        mockMvc.get("/api/v1/accounts") { with(Auth.ops()) }.andExpect { jsonPath("$.length()") { value(2) } }
    }

    @Test
    fun `someone else's account is a 404, so ids cannot be probed`() {
        val bobs = accounts.open("Bob", "IDR", 1_000, ownerId = BOB)

        mockMvc.get("/api/v1/accounts/${bobs.id}") { with(alice) }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/accounts/${bobs.id}/entries") { with(alice) }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/accounts/${bobs.id}/notifications") { with(alice) }.andExpect { status { isNotFound() } }

        mockMvc.get("/api/v1/accounts/${bobs.id}") { with(bob) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/accounts/${bobs.id}") { with(Auth.ops()) }.andExpect { status { isOk() } }
    }

    @Test
    fun `you can send to anyone but only from your own account`() {
        val alices = accounts.open("Alice", "IDR", 100_000, ownerId = ALICE)
        val bobs = accounts.open("Bob", "IDR", 100_000, ownerId = BOB)

        postTransfer(alice, alices.id, bobs.id, 1_000).andExpect { status { isCreated() } }

        // Alice tries to pull money out of Bob's account: she can't see it, so 404.
        postTransfer(alice, bobs.id, alices.id, 1_000).andExpect { status { isNotFound() } }
        // OPS can see Bob's account but may not move his money: 403.
        postTransfer(Auth.ops(), bobs.id, alices.id, 1_000).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCOUNT_NOT_OWNED") }
        }
        postTransfer(null, alices.id, bobs.id, 1_000).andExpect { status { isUnauthorized() } }

        assertEquals(99_000L, accounts.get(alices.id).balance)
        assertEquals(101_000L, accounts.get(bobs.id).balance)
    }

    @Test
    fun `accounts from before authentication cannot be sent from`() {
        val legacy = accounts.open("Legacy", "IDR", 50_000) // no owner
        val bobs = accounts.open("Bob", "IDR", 0, ownerId = BOB)

        postTransfer(bob, legacy.id, bobs.id, 1_000).andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/accounts/${legacy.id}") { with(Auth.ops()) }.andExpect { status { isOk() } }
    }

    @Test
    fun `a transfer is visible to its sender, its receiver and OPS only`() {
        val alices = accounts.open("Alice", "IDR", 10_000, ownerId = ALICE)
        val bobs = accounts.open("Bob", "IDR", 0, ownerId = BOB)
        val carols = accounts.open("Carol", "IDR", 0, ownerId = CAROL)
        val transfer = transfers.transfer(UUID.randomUUID().toString(), TransferCommand(alices.id, bobs.id, 1_000, "IDR", null)).transfer

        mockMvc.get("/api/v1/transfers/${transfer.id}") { with(alice) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/transfers/${transfer.id}") { with(bob) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/transfers/${transfer.id}") { with(Auth.ops()) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/transfers/${transfer.id}") { with(Auth.customer(CAROL)) }.andExpect { status { isNotFound() } }
        assertEquals(0L, accounts.get(carols.id).balance)
    }

    @Test
    fun `reconciliation is for OPS only`() {
        mockMvc.post("/api/v1/reconciliations") { with(alice) }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        mockMvc.post("/api/v1/reconciliations") { with(Auth.ops()) }.andExpect { status { isCreated() } }
    }

    private companion object {
        const val ALICE = "alice-sub"
        const val BOB = "bob-sub"
        const val CAROL = "carol-sub"
    }
}
