package io.nekohasekai.sfa.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream

class ConnectionReportTest {
    @Test fun reportNeverIncludesArbitraryNodeNamesProtocolsOrLogMessages() {
        val secret = "https://example.com/sub?t=TOPSECRET user@example.com 192.0.2.42"
        val node = NodeSnapshot.of(secret, secret)
        val events = EventSummary.from(listOf("EVENT [сторож] $secret", "EVENT [сеть] $secret"))
        val report = ConnectionReport("test", "test", 35, 1, 2, node, node, listOf(10, null, 30), 1024, 50, events).json()
        assertFalse(report.contains("TOPSECRET"))
        assertFalse(report.contains("example.com"))
        assertFalse(report.contains("192.0.2.42"))
        assertFalse(report.contains("user@"))
        assertTrue(JSONObject(report).getJSONObject("before").isNull("protocol"))
        assertEquals(2, JSONObject(report).getInt("successfulProbes"))
        assertEquals(20.0, JSONObject(report).getDouble("medianMs"), 0.01)
    }

    @Test fun downloadStopsAtOneMiBEvenIfServerIgnoresRange() {
        val input = ByteArrayInputStream(ByteArray(2 * DiagnosticLimits.MAX_DOWNLOAD))
        assertEquals(DiagnosticLimits.MAX_DOWNLOAD, DiagnosticLimits.readBounded(input) {})
        assertEquals(DiagnosticLimits.MAX_DOWNLOAD, input.available())
    }

    @Test fun boundedDownloadCanBeCancelled() {
        assertThrows(IllegalStateException::class.java) {
            DiagnosticLimits.readBounded(ByteArrayInputStream(ByteArray(32))) { error("cancelled") }
        }
    }

    @Test fun failedProbesAndChangedNodeAreExplicit() {
        val report = ConnectionReport("test", "test", 35, 1, 2,
            NodeSnapshot.of("one", "vless"), NodeSnapshot.of("two", "hysteria2"),
            listOf(null, null, null), 0, null, EventSummary(0, 0, 0, 0))
        val json = JSONObject(report.json())
        assertTrue(json.getBoolean("nodeChanged"))
        assertTrue(json.isNull("medianMs"))
        assertTrue(json.isNull("downloadMs"))
    }

    @Test fun serializerRejectsUntrustedFieldsEvenIfFactoryWasBypassed() {
        val report = ConnectionReport("test", "test", 35, 1, 2,
            NodeSnapshot("TOKEN-secret", "TOKEN-secret"), NodeSnapshot(null, null),
            emptyList(), 0, null, EventSummary(0, 0, 0, 0)).json()
        assertFalse(report.contains("TOKEN-secret"))
    }
}
