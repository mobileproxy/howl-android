package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        // ★ 15.10.2026: все пути импорта проходят одну проверку до выбора профиля.
        if (profile.typed.type == TypedProfile.Type.Remote ||
            java.io.File(profile.typed.path).readText().trim() != "{}"
        ) {
            io.nekohasekai.sfa.profile.ProfileSafety.receive(profile, java.io.File(profile.typed.path).readText())
        }
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return instance.profileDao().delete(profile).also { deleted ->
                if (deleted > 0) io.nekohasekai.sfa.health.SelectionPreferenceRuntime.store.removeProfile(profile.id, true)
            }
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().delete(profiles).also { deleted ->
                // A partial delete must not erase preferences of a profile still present.
                if (deleted > 0) profiles.forEach { profile ->
                    if (instance.profileDao().get(profile.id) == null)
                        io.nekohasekai.sfa.health.SelectionPreferenceRuntime.store.removeProfile(profile.id, true)
                }
            }
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> = instance.profileDao().list()

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
