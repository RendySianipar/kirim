package dev.rendy.kirim.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Typed subclass so withExposedPorts() returns a usable type in Kotlin (GenericContainer<SELF>). */
class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)

/**
 * Real PostgreSQL, Kafka and Redis in Docker via Testcontainers; @ServiceConnection
 * wires their URLs into Spring automatically. Needs Docker running.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

    @Bean
    @ServiceConnection
    fun kafka(): ConfluentKafkaContainer = ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): RedisContainer = RedisContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
}

/**
 * Base class for integration tests. All subclasses share one Spring context
 * (and one pair of containers) because the configuration is identical.
 * The outbox scheduler is switched off in the "test" profile so tests drive
 * the relay explicitly and stay deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql(
            "TRUNCATE reconciliation_mismatches, reconciliation_runs, notifications, processed_events, outbox_events, idempotency_keys, ledger_entries, transfers, accounts CASCADE",
        ).update()
        // Rate-limit counters and cached transfers must not leak between tests.
        redis.execute(RedisCallback<Any?> { connection ->
            connection.serverCommands().flushDb()
            null
        })
    }
}

/** A clock tests can move forward by hand. */
class MutableClock(private var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
