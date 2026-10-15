package io.nekohasekai.sfa.profile

import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.subscription.SubscriptionConverter
import io.nekohasekai.sfa.utils.AppEventLog
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

object ProfileSafety {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun mutex(profile: Profile) = locks.getOrPut(profile.typed.path) { Mutex() }
    private fun source(profile: Profile) = if (profile.typed.type == TypedProfile.Type.Remote) profile.typed.remoteURL else "local"
    private fun store(profile: Profile) = SafeProfileStore(
        File(profile.typed.path + ".safe"), source(profile), ::readAtomic, ::writeAtomic,
        { raw -> validate(raw, profile.name, source(profile)) },
    )

    fun message(error: Throwable): String {
        val kind = (error as? ProfileFailure)?.kind
        val resource = when (kind) {
            ProfileFailure.Kind.EMPTY -> R.string.profile_safe_empty
            ProfileFailure.Kind.CHANGED -> R.string.profile_safe_changed
            ProfileFailure.Kind.NETWORK -> R.string.profile_safe_network
            else -> R.string.profile_safe_invalid
        }
        return Application.application.getString(resource)
    }

    private fun validate(raw: String, name: String, url: String): String {
        try {
            require(raw.toByteArray(Charsets.UTF_8).size <= ProfileDocument.MAX_BYTES)
            val content = SubscriptionConverter.normalize(raw, name)
            if (ProfileDocument.inspect(content, url)) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
            Libbox.checkConfig(content)
            return content
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProfileFailure) {
            throw e
        } catch (_: Exception) {
            throw ProfileFailure(ProfileFailure.Kind.INVALID)
        }
    }

    suspend fun receive(profile: Profile, raw: String): String = mutex(profile).withLock {
        receiveLocked(profile, raw)
    }

    private fun receiveLocked(profile: Profile, raw: String): String = try {
        val content = store(profile).receive(raw)
        // Compatibility copy for profile viewer/export; service reads the atomic envelope.
        writeAtomic(File(profile.typed.path), content)
        content
    } catch (e: ProfileFailure) {
        if (e.kind == ProfileFailure.Kind.EMPTY) store(profile).blockEmpty()
        throw e
    }

    /** Shared by background updates, manual refresh and URL replacement. */
    suspend fun refresh(profile: Profile): Boolean = mutex(profile).withLock {
        val current = ProfileManager.get(profile.id) ?: throw ProfileFailure(ProfileFailure.Kind.CHANGED)
        if (source(current) != source(profile)) throw ProfileFailure(ProfileFailure.Kind.CHANGED)
        val raw = fetch(source(profile))
        currentCoroutineContext().ensureActive()
        val before = runCatching { readAtomic(File(profile.typed.path)) }.getOrNull()
        val content = receiveLocked(current, raw)
        current.typed.lastUpdated = Date()
        ProfileManager.update(current)
        profile.typed.lastUpdated = current.typed.lastUpdated
        before != content
    }

    suspend fun changeSource(profile: Profile, url: String) = mutex(profile).withLock {
        // Validate first, then bind to the new URL. No old-source backup is reused.
        val oldUrl = profile.typed.remoteURL
        val raw = fetch(url)
        currentCoroutineContext().ensureActive()
        validate(raw, profile.name, url)
        val oldState = readAtomic(File(profile.typed.path + ".safe"))
        val oldContent = readAtomic(File(profile.typed.path))
        profile.typed.remoteURL = url
        try {
            receiveLocked(profile, raw)
            profile.typed.lastUpdated = Date()
            ProfileManager.update(profile)
        } catch (e: Exception) {
            profile.typed.remoteURL = oldUrl
            if (oldState != null) writeAtomic(File(profile.typed.path + ".safe"), oldState)
            else AtomicFile(File(profile.typed.path + ".safe")).delete()
            if (oldContent != null) writeAtomic(File(profile.typed.path), oldContent)
            throw e
        }
    }

    private fun fetch(url: String): String = try {
        HTTPClient().use { it.getString(url) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw ProfileFailure(ProfileFailure.Kind.NETWORK)
    }

    suspend fun prepare(profile: Profile): SafeProfileStore.Prepared = mutex(profile).withLock {
        val prepared = store(profile).prepare {
            readAtomic(File(profile.typed.path)) ?: throw ProfileFailure(ProfileFailure.Kind.INVALID)
        }
        if (prepared.backup) AppEventLog.log("профиль", Application.application.getString(R.string.profile_safe_backup))
        prepared
    }

    suspend fun backup(profile: Profile): SafeProfileStore.Prepared = mutex(profile).withLock {
        store(profile).backupOnly()
    }

    suspend fun applied(profile: Profile, prepared: SafeProfileStore.Prepared) = mutex(profile).withLock {
        val current = ProfileManager.get(profile.id) ?: return@withLock
        if (source(current) == source(profile)) store(current).markApplied(prepared)
    }

    suspend fun summary(profile: Profile): SafeProfileStore.Summary = mutex(profile).withLock { store(profile).summary() }

    suspend fun statusText(profile: Profile): String {
        val state = summary(profile)
        val context = Application.application
        if (state.blocked) return context.getString(R.string.profile_safe_empty)
        fun date(value: Long): String = if (value == 0L) "—" else
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(Date(value))
        return context.getString(R.string.profile_safe_status, date(state.receivedAt), date(state.appliedAt)) +
            if (state.pending) "\n" + context.getString(R.string.profile_safe_pending) else ""
    }

    private fun readAtomic(file: File): String? = try {
        if (!file.exists() && !File(file.path + ".bak").exists()) null
        else AtomicFile(file).openRead().use { stream ->
            val bytes = stream.readBytes()
            require(bytes.size <= ProfileDocument.MAX_BYTES * 3 + 65536)
            bytes.toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { throw ProfileFailure(ProfileFailure.Kind.STORAGE) }

    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw ProfileFailure(ProfileFailure.Kind.STORAGE)
        }
    }
}
