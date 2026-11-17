package dev.rendy.kirim.outbox

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class BackoffTest {

    private val backoff = Backoff(initial = Duration.ofSeconds(1), multiplier = 2.0, max = Duration.ofSeconds(30))

    @Test
    fun `delay doubles with each attempt`() {
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 16L),
            (1..5).map { backoff.delayFor(it).seconds },
        )
    }

    @Test
    fun `delay is capped at the maximum`() {
        assertEquals(Duration.ofSeconds(30), backoff.delayFor(6))
        assertEquals(Duration.ofSeconds(30), backoff.delayFor(50))
    }

    @Test
    fun `attempts start at one`() {
        assertThrows<IllegalArgumentException> { backoff.delayFor(0) }
    }
}
