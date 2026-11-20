package dev.rendy.kirim.transfer

import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.common.TransferNotFoundException
import dev.rendy.kirim.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferCacheIntegrationTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var service: TransferService
    @Autowired lateinit var repository: TransferRepository
    @Autowired lateinit var meters: MeterRegistry

    private fun count(result: String) = meters.counter("kirim.cache", "cache", "transfers", "result", result).count()

    private fun createTransfer(): Transfer {
        val alice = accounts.open("Alice", "IDR", 50_000)
        val bob = accounts.open("Bob", "IDR", 0)
        return service.transfer(UUID.randomUUID().toString(), TransferCommand(alice.id, bob.id, 7_000, "IDR", "kopi")).transfer
    }

    @Test
    fun `creating a transfer does not cache it, the first read fills the cache and the second is a hit`() {
        val transfer = createTransfer()
        assertFalse(redis.hasKey("kirim:transfer:${transfer.id}"), "nothing is cached inside the write transaction")

        val misses = count("miss")
        val hits = count("hit")

        mockMvc.get("/api/v1/transfers/${transfer.id}").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/transfers/${transfer.id}").andExpect {
            status { isOk() }
            jsonPath("$.description") { value("kopi") }
        }

        assertEquals(misses + 1, count("miss"))
        assertEquals(hits + 1, count("hit"))
        val ttl = redis.getExpire("kirim:transfer:${transfer.id}")
        assertTrue(ttl in 1L..600L, "cached entries expire, got TTL $ttl")
    }

    @Test
    fun `the cached copy is identical to the database row`() {
        val transfer = createTransfer()
        service.get(transfer.id) // fills the cache

        val cached = service.get(transfer.id)

        assertEquals(repository.findById(transfer.id), cached)
    }

    @Test
    fun `unknown transfers are a 404 and are not cached`() {
        val ghost = UUID.randomUUID()

        assertThrows<TransferNotFoundException> { service.get(ghost) }

        assertFalse(redis.hasKey("kirim:transfer:$ghost"))
    }
}
