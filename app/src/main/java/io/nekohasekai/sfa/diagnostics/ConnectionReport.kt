package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.profile.SafeProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Strict allowlist: raw profile names, nodes, URLs, addresses and logs cannot enter JSON. */
data class NodeSnapshot(val alias: String?, val protocol: String?) {
    companion object {
        val protocols = setOf("vless", "vmess", "shadowsocks", "trojan", "hysteria2", "tuic", "wireguard", "amneziawg", "openconnect", "trusttunnel", "socks", "http")
        fun of(tag: String?, protocol: String?): NodeSnapshot {
            return NodeSnapshot(tag?.let { "node-" + SafeProfileStore.hash(it).take(12) }, protocol?.takeIf { it in protocols })
        }
    }
}

data class EventSummary(val samples: Int, val networkChanges: Int, val recoveries: Int, val coreEvents: Int) {
    companion object {
        fun from(lines: List<String>): EventSummary {
            // Classify bounded local history, never copy a line or message into the report.
            val tail = lines.takeLast(2000)
            return EventSummary(
                tail.size,
                tail.count { it.contains("EVENT [сеть]") },
                tail.count { it.contains("EVENT [сторож]") },
                tail.count { it.contains("EVENT [ядро]") },
            )
        }
    }
}

data class ConnectionReport(
    val appVersion: String,
    val coreVersion: String,
    val sdk: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val before: NodeSnapshot,
    val after: NodeSnapshot,
    val delays: List<Long?>,
    val bytes: Int,
    val downloadMs: Long?,
    val events: EventSummary,
) {
    fun json(): String {
        fun node(value: NodeSnapshot) = JSONObject()
            .put("alias", value.alias?.takeIf { it.matches(Regex("node-[0-9a-f]{12}")) } ?: JSONObject.NULL)
            .put("protocol", value.protocol?.takeIf { it in NodeSnapshot.protocols } ?: JSONObject.NULL)
        val successful = delays.filterNotNull().sorted()
        return JSONObject()
            .put("schema", 1).put("appVersion", appVersion).put("coreVersion", coreVersion).put("androidSdk", sdk)
            .put("startedAtEpochMs", startedAt).put("finishedAtEpochMs", finishedAt)
            .put("route", "application_route_with_user_routing_rules")
            .put("before", node(before)).put("after", node(after)).put("nodeChanged", before != after)
            .put("probesMs", JSONArray().apply { delays.forEach { put(it ?: JSONObject.NULL) } })
            .put("successfulProbes", successful.size)
            .put("medianMs", if (successful.isEmpty()) JSONObject.NULL else
                (successful[(successful.size - 1) / 2] + successful[successful.size / 2]) / 2.0)
            .put("spreadMs", if (successful.isEmpty()) JSONObject.NULL else successful.last() - successful.first())
            .put("downloadBytes", bytes).put("downloadMs", downloadMs ?: JSONObject.NULL)
            .put("events", JSONObject().put("sampledLines", events.samples).put("networkChanges", events.networkChanges)
                .put("watchdogEvents", events.recoveries).put("coreEvents", events.coreEvents))
            .toString(2)
    }
}

object DiagnosticLimits {
    const val MAX_DOWNLOAD = 1024 * 1024
    fun readBounded(stream: InputStream, check: () -> Unit): Int {
        var total = 0
        val buffer = ByteArray(8192)
        while (total < MAX_DOWNLOAD) {
            check()
            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_DOWNLOAD - total))
            if (count < 0) break
            total += count
        }
        return total
    }
}
