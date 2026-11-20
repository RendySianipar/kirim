package dev.rendy.kirim.transfer

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("kirim.cache.transfers")
data class TransferCacheProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofMinutes(10),
)

/**
 * Cache-aside for transfers in Redis.
 *
 * Transfers are immutable once committed (COMPLETED or REJECTED, never updated),
 * so a cached copy can never go stale and there is nothing to invalidate. That is
 * why transfers are cached and account balances are not.
 *
 * Only committed data is cached: entries are written on the read path, never
 * inside the transaction that creates the transfer (which could still roll back).
 *
 * Redis errors are logged and treated as a miss: the cache makes reads cheaper,
 * it is never the source of truth.
 */
@Component
class TransferCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val props: TransferCacheProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(id: UUID): Transfer? {
        if (!props.enabled) return null
        return try {
            val json = redis.opsForValue().get(key(id))
            if (json == null) {
                meters.counter("kirim.cache", "cache", "transfers", "result", "miss").increment()
                null
            } else {
                meters.counter("kirim.cache", "cache", "transfers", "result", "hit").increment()
                objectMapper.readValue(json, Transfer::class.java)
            }
        } catch (ex: Exception) {
            meters.counter("kirim.cache", "cache", "transfers", "result", "error").increment()
            log.warn("Transfer cache read failed for {}, falling back to the database: {}", id, ex.message)
            null
        }
    }

    fun put(transfer: Transfer) {
        if (!props.enabled) return
        try {
            redis.opsForValue().set(key(transfer.id), objectMapper.writeValueAsString(transfer), props.ttl)
        } catch (ex: Exception) {
            log.warn("Transfer cache write failed for {}: {}", transfer.id, ex.message)
        }
    }

    private fun key(id: UUID) = "kirim:transfer:$id"
}
