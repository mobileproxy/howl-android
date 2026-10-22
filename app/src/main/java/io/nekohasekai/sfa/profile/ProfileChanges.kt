package io.nekohasekai.sfa.profile

import io.nekohasekai.sfa.subscription.ConfigHardening
import org.json.JSONArray
import org.json.JSONObject

/** Compare effective content without rewriting the received source or its integrity hashes. */
object ProfileChanges {
    fun requiresReload(before: String?, after: String): Boolean {
        if (before == after) return false
        if (before == null) return true
        return runCatching {
            val previous = JSONObject(before)
            val received = JSONObject(after)
            // This policy is unconditional in every runtime; keep all other source fields.
            ConfigHardening.preserveConnections(previous)
            ConfigHardening.preserveConnections(received)
            !equivalent(previous, received)
        }.getOrDefault(true)
    }

    private fun equivalent(before: Any?, after: Any?): Boolean = when {
        before is JSONObject && after is JSONObject -> {
            val keys = before.keys().asSequence().toSet()
            keys == after.keys().asSequence().toSet() && keys.all { equivalent(before.get(it), after.get(it)) }
        }
        before is JSONArray && after is JSONArray ->
            before.length() == after.length() && (0 until before.length()).all { equivalent(before.get(it), after.get(it)) }
        else -> before == after
    }
}
