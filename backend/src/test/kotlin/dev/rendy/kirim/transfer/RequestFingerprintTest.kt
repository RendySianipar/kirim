package dev.rendy.kirim.transfer

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestFingerprintTest {

    private val base = TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 10_000, "IDR", "rent")

    @Test
    fun `same request gives the same fingerprint`() {
        assertEquals(RequestFingerprint.of(base), RequestFingerprint.of(base.copy()))
    }

    @Test
    fun `any change to the request changes the fingerprint`() {
        val original = RequestFingerprint.of(base)

        assertNotEquals(original, RequestFingerprint.of(base.copy(amount = 10_001)))
        assertNotEquals(original, RequestFingerprint.of(base.copy(toAccountId = UUID.randomUUID())))
        assertNotEquals(original, RequestFingerprint.of(base.copy(description = "food")))
    }

    @Test
    fun `fingerprint is a 64-char hex SHA-256`() {
        val fp = RequestFingerprint.of(base)
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
