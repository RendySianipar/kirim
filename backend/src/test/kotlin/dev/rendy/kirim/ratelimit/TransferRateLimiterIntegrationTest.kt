package dev.rendy.kirim.ratelimit

import dev.rendy.kirim.support.IntegrationTest
import dev.rendy.kirim.support.MutableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The limiter against a real Redis, with a clock the test controls so window
 * boundaries are exact instead of depending on when the test happens to run.
 */
class TransferRateLimiterIntegrationTest : IntegrationTest() {

    /** 10 seconds into a minute, so the current window has 50 seconds left. */
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:10Z"))

    private fun limiter(props: RateLimitProperties, template: StringRedisTemplate = redis) =
        TransferRateLimiter(template, props, clock, SimpleMeterRegistry())

    private val threePerMinute = RateLimitProperties(transfersPerWindow = 3, window = Duration.ofMinutes(1))

    @Test
    fun `allows up to the limit, then rejects with the seconds left in the window`() {
        val limiter = limiter(threePerMinute)
        val account = UUID.randomUUID()

        repeat(3) { limiter.acquire(account) }
        val ex = assertThrows<RateLimitExceededException> { limiter.acquire(account) }

        assertEquals(50L, ex.retryAfterSeconds)
    }

    @Test
    fun `a new window starts a new count`() {
        val limiter = limiter(threePerMinute)
        val account = UUID.randomUUID()
        repeat(3) { limiter.acquire(account) }
        assertThrows<RateLimitExceededException> { limiter.acquire(account) }

        clock.advance(Duration.ofSeconds(50)) // 00:01:00, next window

        assertDoesNotThrow { limiter.acquire(account) }
    }

    @Test
    fun `accounts are limited independently`() {
        val limiter = limiter(threePerMinute)
        val busy = UUID.randomUUID()
        repeat(3) { limiter.acquire(busy) }

        assertDoesNotThrow { limiter.acquire(UUID.randomUUID()) }
    }

    @Test
    fun `every counter gets a TTL so Redis cleans up after itself`() {
        val account = UUID.randomUUID()
        limiter(threePerMinute).acquire(account)

        val windowNumber = clock.instant().epochSecond / 60
        val ttl = redis.getExpire("kirim:rl:transfers:$account:$windowNumber")

        assertTrue(ttl in 1L..60L, "expected a TTL of at most one window, got $ttl")
    }

    @Test
    fun `fails open when Redis is down, by default`() {
        withUnreachableRedis { broken ->
            assertDoesNotThrow { limiter(threePerMinute, broken).acquire(UUID.randomUUID()) }
        }
    }

    @Test
    fun `fails closed with 503 when configured to`() {
        withUnreachableRedis { broken ->
            assertThrows<RateLimiterUnavailableException> {
                limiter(threePerMinute.copy(failOpen = false), broken).acquire(UUID.randomUUID())
            }
        }
    }

    @Test
    fun `does not touch Redis when disabled`() {
        withUnreachableRedis { broken ->
            assertDoesNotThrow {
                limiter(threePerMinute.copy(enabled = false, failOpen = false), broken).acquire(UUID.randomUUID())
            }
        }
    }

    /** A template pointing at a port nothing listens on. */
    private fun withUnreachableRedis(block: (StringRedisTemplate) -> Unit) {
        val clientConfig = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build()
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration("localhost", 1), clientConfig)
        factory.afterPropertiesSet()
        factory.start()
        try {
            block(StringRedisTemplate(factory))
        } finally {
            factory.destroy()
        }
    }
}
