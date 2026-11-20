package dev.rendy.kirim.ratelimit

import dev.rendy.kirim.common.ApiException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("kirim.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    /** New transfers one source account may start per window. */
    val transfersPerWindow: Long = 10,
    val window: Duration = Duration.ofMinutes(1),
    /**
     * What to do when Redis is unreachable. true = allow the transfer (the rate
     * limiter is a guard rail; balance and per-transfer limits are still enforced
     * by PostgreSQL). false = reject with 503.
     */
    val failOpen: Boolean = true,
)

class RateLimitExceededException(val retryAfterSeconds: Long) :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many transfers from this account; retry in $retryAfterSeconds s")

class RateLimiterUnavailableException :
    ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMITER_UNAVAILABLE", "Transfers are temporarily unavailable; retry shortly")

/**
 * Fixed-window rate limit per source account, stored in Redis.
 *
 * Key: kirim:rl:transfers:{accountId}:{windowNumber}. INCR and EXPIRE run in one
 * Lua script, so the counter can never be left without a TTL (which would block
 * the account forever) and every app instance shares the same counter.
 *
 * Fixed windows allow a burst of up to 2x the limit across a window boundary.
 * That is acceptable for a guard rail; a sliding window or token bucket would
 * fix it at the cost of more Redis work per request.
 */
@Component
class TransferRateLimiter(
    private val redis: StringRedisTemplate,
    private val props: RateLimitProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val script = DefaultRedisScript(
        """
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
          redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return count
        """.trimIndent(),
        Long::class.javaObjectType,
    )

    /** Throws [RateLimitExceededException] when [accountId] is over its limit for the current window. */
    fun acquire(accountId: UUID) {
        if (!props.enabled) return

        val windowSeconds = props.window.seconds.coerceAtLeast(1)
        val nowSeconds = clock.instant().epochSecond
        val windowNumber = nowSeconds / windowSeconds
        val key = "kirim:rl:transfers:$accountId:$windowNumber"

        val count: Long? = try {
            redis.execute(script, listOf(key), windowSeconds.toString())
        } catch (ex: DataAccessException) {
            meters.counter("kirim.ratelimit", "result", "error").increment()
            if (props.failOpen) {
                log.warn("Redis unavailable, allowing transfer from {} without rate limiting: {}", accountId, ex.message)
                return
            }
            throw RateLimiterUnavailableException()
        }

        if (count != null && count > props.transfersPerWindow) {
            meters.counter("kirim.ratelimit", "result", "rejected").increment()
            throw RateLimitExceededException(retryAfterSeconds = windowSeconds - (nowSeconds % windowSeconds))
        }
        meters.counter("kirim.ratelimit", "result", "allowed").increment()
    }
}
