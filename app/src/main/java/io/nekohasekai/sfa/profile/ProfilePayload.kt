package io.nekohasekai.sfa.profile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object ProfilePayload {
    fun read(stream: InputStream, limit: Int = ProfileDocument.MAX_BYTES, check: () -> Unit = {}): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check()
            // Read at most one extra byte so unknown Content-Length cannot exhaust memory.
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - bytes.size() + 1))
            if (count < 0) break
            require(bytes.size() + count <= limit)
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
