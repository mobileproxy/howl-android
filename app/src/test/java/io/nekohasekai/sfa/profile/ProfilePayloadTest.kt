package io.nekohasekai.sfa.profile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ProfilePayloadTest {
    @Test fun unknownLengthCannotExceedLimit() {
        val stream = ByteArrayInputStream(ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) { ProfilePayload.read(stream, 10) }
        assertEquals(89, stream.available())
    }
    @Test fun utf8MustBeValid() {
        assertThrows(Exception::class.java) { ProfilePayload.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
    }
    @Test fun deadlineOrCancellationStopsReading() {
        assertThrows(IllegalStateException::class.java) {
            ProfilePayload.read(ByteArrayInputStream(ByteArray(100))) { error("cancelled") }
        }
    }
    @Test fun exactLimitAndValidUnicodeAccepted() {
        assertEquals("ёж", ProfilePayload.read(ByteArrayInputStream("ёж".toByteArray(Charsets.UTF_8)), 4))
    }
}
