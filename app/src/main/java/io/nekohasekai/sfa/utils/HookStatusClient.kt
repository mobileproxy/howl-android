package io.nekohasekai.sfa.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import io.nekohasekai.sfa.bg.ParceledListSlice
import io.nekohasekai.sfa.xposed.HookStatusKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

object HookStatusClient {
    data class Status(val active: Boolean, val lastPatchedAt: Long, val version: Int, val systemPid: Int)

    private val statusFlow = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = statusFlow

    @Volatile
    private var appContext: Context? = null

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "howl-hook-status").apply { isDaemon = true }
    }

    fun register(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /**
     * Обновить статус модуля.
     *
     * Внутри — синхронный IPC в system_server. Экраны зовут это из LaunchedEffect и ON_RESUME,
     * то есть с главного потока, и пока система занята (например, сразу после переключения
     * сервера) экран Настроек просто не открывался. Поэтому с главного потока уходим в фон:
     * результат всё равно приезжает через statusFlow, ждать его никто не обязан.
     */
    fun refresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            worker.execute { refreshBlocking() }
        } else {
            refreshBlocking()
        }
    }

    private fun refreshBlocking() {
        val context = appContext ?: return
        val binder = ConnectivityBinderUtils.getBinder(context) ?: run {
            statusFlow.value = null
            return
        }
        ConnectivityBinderUtils.withParcel { data, reply ->
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            val ok = binder.transact(HookStatusKeys.TRANSACTION_STATUS, data, reply, 0)
            if (!ok) {
                statusFlow.value = null
                return
            }
            reply.readException()
            statusFlow.value = Status(
                active = reply.readInt() != 0,
                lastPatchedAt = reply.readLong(),
                version = reply.readInt(),
                systemPid = reply.readInt(),
            )
        }
    }

    fun getInstalledPackages(context: Context, flags: Long, userId: Int): List<PackageInfo>? {
        val binder = ConnectivityBinderUtils.getBinder(context) ?: return null
        return ConnectivityBinderUtils.withParcel { data, reply ->
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            data.writeLong(flags)
            data.writeInt(userId)
            val ok = binder.transact(HookStatusKeys.TRANSACTION_GET_INSTALLED_PACKAGES, data, reply, 0)
            if (!ok) return@withParcel null
            reply.readException()
            val slice = ParceledListSlice.CREATOR.createFromParcel(reply, PackageInfo::class.java.classLoader)
            @Suppress("UNCHECKED_CAST")
            (slice as ParceledListSlice<PackageInfo>).list
        }
    }
}
