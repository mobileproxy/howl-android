package io.nekohasekai.sfa.profile

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** ★ 15.10.2026: полученный кандидат не становится рабочим резервом до запуска ядра. */
class SafeProfileStore(
    private val stateFile: File,
    private val source: String,
    private val readAtomic: (File) -> String?,
    private val writeAtomic: (File, String) -> Unit,
    private val validate: (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Prepared(val content: String, val sourceHash: String, val hash: String, val backup: Boolean)
    data class Summary(val receivedAt: Long, val appliedAt: Long, val pending: Boolean, val blocked: Boolean)

    private val binding = hash(source)

    private fun state(): JSONObject? {
        val raw = readAtomic(stateFile) ?: return null
        val value = try { JSONObject(raw) } catch (_: Exception) { throw ProfileFailure(ProfileFailure.Kind.STORAGE) }
        if (value.optInt("version") != 1) throw ProfileFailure(ProfileFailure.Kind.STORAGE)
        return value.takeIf { it.optString("source") == binding }
    }

    private fun save(state: JSONObject) = writeAtomic(stateFile, state.toString())

    fun receive(raw: String): String {
        val content = validate(raw)
        val state = state() ?: JSONObject().put("version", 1).put("source", binding)
        state.put("received", content).put("receivedHash", hash(content)).put("receivedAt", now())
        state.put("blocked", false)
        save(state)
        return content
    }

    fun blockEmpty() {
        val state = state() ?: JSONObject().put("version", 1).put("source", binding)
        state.put("blocked", true).put("receivedAt", now())
        state.put("backupDisabled", true)
        state.remove("received")
        state.remove("receivedHash")
        save(state)
    }

    fun prepare(legacy: () -> String): Prepared {
        val state = state()
        if (state?.optBoolean("blocked") == true) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
        if (state != null) {
            val received = state.optString("received")
            if (received.isNotBlank()) {
                try {
                    val content = validate(received)
                    if (hash(content) != state.getString("receivedHash")) throw ProfileFailure(ProfileFailure.Kind.INVALID)
                    return Prepared(content, binding, hash(content), false)
                } catch (_: ProfileFailure) {
                    return backup(state)
                }
            }
            return backup(state)
        }
        // A state for another source must never adopt the previous URL's file as a backup.
        if (readAtomic(stateFile) != null) throw ProfileFailure(ProfileFailure.Kind.CHANGED)
        val content = validate(legacy())
        receive(content)
        return Prepared(content, binding, hash(content), false)
    }

    fun backupOnly(): Prepared = backup(state() ?: throw ProfileFailure(ProfileFailure.Kind.INVALID))

    private fun backup(state: JSONObject): Prepared {
        if (state.optBoolean("blocked")) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
        if (state.optBoolean("backupDisabled")) throw ProfileFailure(ProfileFailure.Kind.INVALID)
        val raw = state.optString("applied")
        if (raw.isBlank()) throw ProfileFailure(ProfileFailure.Kind.INVALID)
        val content = validate(raw)
        if (hash(content) != state.optString("appliedHash")) throw ProfileFailure(ProfileFailure.Kind.STORAGE)
        return Prepared(content, binding, hash(content), true)
    }

    fun markApplied(prepared: Prepared): Boolean {
        val state = state() ?: return false
        if (prepared.sourceHash != binding || state.optBoolean("blocked")) return false
        val expectedHash = if (prepared.backup) state.optString("appliedHash") else state.optString("receivedHash")
        if (prepared.hash != expectedHash) return false
        if (state.optString("appliedHash") == prepared.hash && !state.optBoolean("backupDisabled")) return true
        state.put("applied", prepared.content).put("appliedHash", prepared.hash).put("appliedAt", now())
        state.put("backupDisabled", false)
        save(state)
        return true
    }

    fun summary(): Summary {
        val state = state() ?: return Summary(0, 0, false, false)
        return Summary(
            state.optLong("receivedAt"), state.optLong("appliedAt"),
            state.optString("receivedHash") != state.optString("appliedHash") || state.optBoolean("backupDisabled"),
            state.optBoolean("blocked"),
        )
    }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

class ProfileFailure(val kind: Kind) : IllegalStateException(kind.name) {
    enum class Kind { NETWORK, INVALID, EMPTY, CHANGED, STORAGE }
}
