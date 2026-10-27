package io.nekohasekai.sfa.health

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One atomic preference value, separate from profiles/configs/backups. A failed
 * write MUST leave the old value intact (for example, one Room transaction).
 * This contract does not promise rollback for an arbitrary non-atomic writer.
 */
interface AtomicPreferenceStorage {
    fun read(): String?
    fun writeAtomically(value: String)
}
data class SelectionPreference(val profile: Long, val selector: String, val target: String, val revision: Long)
enum class PreferenceDataState { Empty, Ready, Corrupt, UnsupportedVersion, Unavailable }
enum class PreferenceWriteResult { Stored, Unchanged, Rejected, ReadOnly, WriteFailed }

/** Capture before starting asynchronous native selection, including same-item clicks. */
class ManualSelectionIntent internal constructor(internal val owner: Any,
    val profile: Long, val selector: String, val target: String)

/** One process-owned instance serializes manual intent and the injected atomic writer. */
class ProfileSelectionPreferences(private val storage: AtomicPreferenceStorage) {
    private data class Key(val profile: Long, val selector: String)
    private data class Document(val state: PreferenceDataState, val revision: Long = 0,
        val entries: Map<Key, SelectionPreference> = emptyMap()) {
        val writable get() = state == PreferenceDataState.Empty || state == PreferenceDataState.Ready
    }
    private val owner = Any()
    private var latestIntent: ManualSelectionIntent? = null

    @Synchronized fun read(profile: Long, selector: String): SelectionPreference? =
        if (profile > 0 && validTag(selector)) load().entries[Key(profile, selector)] else null

    @Synchronized fun dataState(): PreferenceDataState = load().state

    @Synchronized fun beginManual(profile: Long, selector: String, target: String,
        local: Boolean = true): ManualSelectionIntent? {
        if (!local) return null
        // A newer local intent wins even if its native acknowledgment later fails.
        latestIntent = null
        if (profile <= 0 || !validTag(selector) || !validTag(target)) return null
        return ManualSelectionIntent(owner, profile, selector, target).also { latestIntent = it }
    }

    @Synchronized fun acknowledgeManual(intent: ManualSelectionIntent, acknowledged: Boolean,
        stillCurrent: () -> Boolean): PreferenceWriteResult {
        fun current() = intent.owner === owner && latestIntent === intent && guard(stillCurrent) && latestIntent === intent
        if (!acknowledged) {
            if (latestIntent === intent) latestIntent = null
            return PreferenceWriteResult.Rejected
        }
        if (!current()) return PreferenceWriteResult.Rejected
        val document = load()
        if (!document.writable || document.revision == Long.MAX_VALUE) return PreferenceWriteResult.ReadOnly
        val key = Key(intent.profile, intent.selector)
        if (key !in document.entries && document.entries.size >= MAX_ENTRIES) return PreferenceWriteResult.Rejected
        val revision = document.revision + 1
        val entries = document.entries + (key to SelectionPreference(intent.profile, intent.selector, intent.target, revision))
        val encoded = encode(revision, entries.values)
        if (encoded.length > MAX_DOCUMENT_CHARS) return PreferenceWriteResult.Rejected
        // Reads/encoding may take time; revalidate original intent immediately before commit.
        if (!current()) return PreferenceWriteResult.Rejected
        return write(encoded).also { if (it == PreferenceWriteResult.Stored) latestIntent = null }
    }

    /** The caller's guard must capture the ORIGINAL manual ticket/revision, not create one here. */
    @Synchronized
    fun recordManual(profile: Long, selector: String, target: String, acknowledged: Boolean = true,
        local: Boolean = true, stillCurrent: () -> Boolean): PreferenceWriteResult {
        // This acknowledged convenience path can receive a late external callback.
        // Reject it before creating an intent that could cancel a newer pending click.
        if (!local || !acknowledged || !guard(stillCurrent)) return PreferenceWriteResult.Rejected
        val intent = beginManual(profile, selector, target, local) ?: return PreferenceWriteResult.Rejected
        return acknowledgeManual(intent, acknowledged, stillCurrent)
    }

    @Synchronized fun removeProfile(profile: Long, deletionSucceeded: Boolean): PreferenceWriteResult {
        if (!deletionSucceeded || profile <= 0) return PreferenceWriteResult.Rejected
        if (latestIntent?.profile == profile) latestIntent = null
        val document = load()
        if (!document.writable || document.revision == Long.MAX_VALUE) return PreferenceWriteResult.ReadOnly
        val remaining = document.entries.filterKeys { it.profile != profile }
        if (remaining.size == document.entries.size) return PreferenceWriteResult.Unchanged
        return write(encode(document.revision + 1, remaining.values))
    }

    private fun load(): Document {
        val raw = try { storage.read() } catch (_: Exception) { return Document(PreferenceDataState.Unavailable) }
        if (raw == null) return Document(PreferenceDataState.Empty)
        if (raw.length > MAX_DOCUMENT_CHARS) return Document(PreferenceDataState.Corrupt)
        if (raw.isBlank()) return Document(PreferenceDataState.Empty)
        return try {
            val tokenizer = JSONTokener(raw)
            val json = tokenizer.nextValue() as? JSONObject ?: error("object")
            require(tokenizer.nextClean() == '\u0000')
            val version = integer(json.get("version"))
            if (version != VERSION) return Document(PreferenceDataState.UnsupportedVersion)
            val revision = integer(json.get("revision"))
            require(revision >= 0)
            val array = json.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES)
            val entries = LinkedHashMap<Key, SelectionPreference>()
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val profile = integer(entry.get("profile"))
                val selector = entry.get("selector") as? String ?: error("selector")
                val target = entry.get("target") as? String ?: error("target")
                val entryRevision = integer(entry.get("revision"))
                require(profile > 0 && validTag(selector) && validTag(target))
                require(entryRevision > 0 && entryRevision <= revision)
                val key = Key(profile, selector)
                require(key !in entries)
                entries[key] = SelectionPreference(profile, selector, target, entryRevision)
            }
            Document(PreferenceDataState.Ready, revision, entries)
        } catch (_: Exception) { Document(PreferenceDataState.Corrupt) }
    }

    private fun write(value: String): PreferenceWriteResult = try {
        storage.writeAtomically(value)
        PreferenceWriteResult.Stored
    } catch (_: Exception) { PreferenceWriteResult.WriteFailed }

    private fun encode(revision: Long, entries: Collection<SelectionPreference>): String {
        val array = JSONArray()
        entries.forEach { entry -> array.put(JSONObject().put("profile", entry.profile)
            .put("selector", entry.selector).put("target", entry.target).put("revision", entry.revision)) }
        return JSONObject().put("version", VERSION).put("revision", revision).put("entries", array).toString()
    }

    private fun guard(stillCurrent: () -> Boolean) = try { stillCurrent() } catch (_: Exception) { false }
    private fun integer(value: Any): Long {
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun validTag(value: String) = value.isNotBlank() && value.length <= MAX_TAG_CHARS && value.none { it.isISOControl() }

    companion object {
        private const val VERSION = 1L
        private const val MAX_ENTRIES = 128
        private const val MAX_TAG_CHARS = 256
        private const val MAX_DOCUMENT_CHARS = 65_536
    }
}
