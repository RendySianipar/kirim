package dev.rendy.kirim.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The HTTP side of rate limiting, with a limit of 2 per day so the window
 * cannot roll over during the test. (This needs its own Spring context, and
 * so its own containers, because the limit is a startup property.)
 */
@TestPropertySource(properties = ["kirim.rate-limit.transfers-per-window=2", "kirim.rate-limit.window=24h"])
class RateLimitApiIntegrationTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var accounts: AccountService

    private fun postTransfer(key: String, from: UUID, to: UUID, amount: Long) = mockMvc.post("/api/v1/transfers") {
        contentType = MediaType.APPLICATION_JSON
        header("Idempotency-Key", key)
        content = objectMapper.writeValueAsString(mapOf("fromAccountId" to from, "toAccountId" to to, "amount" to amount))
    }

    @Test
    fun `over the limit is a 429 with Retry-After, replays are free and the rejected key is not burned`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)

        postTransfer("rl-1", alice.id, bob.id, 1_000).andExpect { status { isCreated() } }
        postTransfer("rl-2", alice.id, bob.id, 1_000).andExpect { status { isCreated() } }

        // A retry of an earlier request is answered from the idempotency record and uses no quota.
        postTransfer("rl-1", alice.id, bob.id, 1_000).andExpect { status { isOk() } }

        postTransfer("rl-3", alice.id, bob.id, 1_000).andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("RATE_LIMITED") }
        }

        assertEquals(98_000L, accounts.get(alice.id).balance, "the limited transfer moved no money")
        val keyRows = jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'rl-3'")
            .query(Long::class.javaObjectType).single()
        assertEquals(0L, keyRows, "the rejected request rolled back, so the client can retry with the same key later")

        // Bob has his own quota.
        postTransfer("rl-bob", bob.id, alice.id, 500).andExpect { status { isCreated() } }
    }
}
