package io.nekohasekai.sfa.vendor

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.xposed.XposedActivation
import kotlinx.coroutines.delay
import java.io.File

enum class InstallMethod {
    PACKAGE_INSTALLER,
    SHIZUKU,
    ROOT,
}

object ApkInstaller {

    private suspend fun stopServiceIfRunning() {
        val commandSocket = File(Application.application.filesDir, "command.sock")
        if (!commandSocket.exists()) {
            return
        }
        BoxService.stop()
        repeat(20) {
            delay(100)
            if (!commandSocket.exists()) {
                return
            }
        }
    }

    fun getConfiguredMethod(): InstallMethod {
        if (HookStatusClient.status.value?.active == true ||
            XposedActivation.isActivated(Application.application)
        ) {
            return InstallMethod.ROOT
        }
        return if (Settings.silentInstallEnabled) {
            InstallMethod.valueOf(Settings.silentInstallMethod)
        } else {
            InstallMethod.PACKAGE_INSTALLER
        }
    }

    suspend fun install(context: Context, apkFile: File, method: InstallMethod = getConfiguredMethod()) {
        when (method) {
            // Тихая установка (root/Shizuku) подменяет пакет сама, без окон — здесь VPN
            // действительно нужно остановить заранее.
            InstallMethod.SHIZUKU -> {
                stopServiceIfRunning()
                ShizukuInstaller.install(apkFile)
            }

            InstallMethod.ROOT -> {
                stopServiceIfRunning()
                RootInstaller.install(apkFile)
            }

            // Обычный путь: VPN НЕ трогаем. Раньше его глушили до установки, а «тихая» установка
            // без привилегий стора не срабатывала — связь рвалась, и ничего не ставилось.
            // Теперь открываем штатное окно Android: пользователь подтверждает, и система сама
            // останавливает приложение в момент замены — то есть в правильный момент.
            InstallMethod.PACKAGE_INSTALLER -> installViaSystemPrompt(context, apkFile)
        }
    }

    /**
     * Системное окно установки. Требует REQUEST_INSTALL_PACKAGES (объявлено в манифесте) и
     * пользовательского разрешения «Установка неизвестных приложений» — Android спросит его сам.
     */
    private fun installViaSystemPrompt(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canSystemSilentInstall(): Boolean = SystemPackageInstaller.canSystemSilentInstall()

    suspend fun canSilentInstall(): Boolean {
        val method = getConfiguredMethod()
        return when (method) {
            InstallMethod.PACKAGE_INSTALLER -> canSystemSilentInstall()
            InstallMethod.SHIZUKU -> ShizukuInstaller.isAvailable() && ShizukuInstaller.checkPermission()
            InstallMethod.ROOT -> RootClient.checkRootAvailable()
        }
    }
}
