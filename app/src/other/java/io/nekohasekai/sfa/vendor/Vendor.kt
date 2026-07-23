package io.nekohasekai.sfa.vendor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageAnalysis
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.compose.screen.qrscan.QRCodeCropArea
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.HowlUpdateChecker
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.update.UpdateState

object Vendor : VendorInterface {
    private const val TAG = "Vendor"

    override fun checkUpdate(activity: Activity, byUser: Boolean) {
        try {
            val updateInfo = checkUpdateAsync()
            if (updateInfo != null) {
                activity.runOnUiThread {
                    showUpdateDialog(activity, updateInfo)
                }
            } else if (byUser) {
                activity.runOnUiThread {
                    showNoUpdatesDialog(activity)
                }
            }
        } catch (e: UpdateCheckException.TrackNotSupported) {
            Log.d(TAG, "checkUpdate: track not supported")
            if (byUser) {
                activity.runOnUiThread {
                    showTrackNotSupportedDialog(activity)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate: ", e)
            if (byUser) {
                activity.runOnUiThread {
                    showNoUpdatesDialog(activity)
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        val message = buildString {
            append(activity.getString(R.string.new_version_available, updateInfo.versionName))
            if (!updateInfo.releaseNotes.isNullOrBlank()) {
                append("\n\n")
                append(updateInfo.releaseNotes.take(500))
                if (updateInfo.releaseNotes.length > 500) {
                    append("...")
                }
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.check_update)
            .setMessage(message)
            .setPositiveButton(R.string.update) { _, _ ->
                // Прямо на APK, а не на страницу-папку (она может отдавать 403): браузер качает
                // файл, тап открывает штатный установщик. Ту же ссылку даёт кнопка в настройках.
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                activity.startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNoUpdatesDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.check_update)
            .setMessage(R.string.no_updates_available)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showTrackNotSupportedDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.check_update)
            .setMessage(R.string.update_track_not_supported)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun createQRCodeAnalyzer(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        onCropArea: ((QRCodeCropArea?) -> Unit)?,
    ): ImageAnalysis.Analyzer? = null

    override val hasCustomUpdate = true

    // Источник один — наш сайт, поэтому выбор источника в настройках не показывается:
    // экран рисует его только при updateSources.size > 1. Прежняя GitHub-проверка искала
    // обновления в репозитории sing-box, то есть у чужого проекта: наших сборок там нет,
    // а чужое приложение поверх Howl не встанет — другой идентификатор и подпись.
    override val updateSources = listOf(UpdateSource.GITHUB)

    override fun checkUpdateAsync(): UpdateInfo? = HowlUpdateChecker().use { checker ->
        checker.checkUpdate()
    }

    override fun scheduleAutoUpdate() {
        UpdateWorker.schedule(io.nekohasekai.sfa.Application.application)
    }

    override suspend fun verifySilentInstallMethod(method: String): Boolean {
        return when (method) {
            "PACKAGE_INSTALLER" -> {
                ApkInstaller.canSystemSilentInstall()
            }
            "SHIZUKU" -> {
                if (!ShizukuInstaller.isAvailable()) {
                    return false
                }
                if (!ShizukuInstaller.checkPermission()) {
                    ShizukuInstaller.requestPermission()
                    return false
                }
                true
            }
            "ROOT" -> RootClient.checkRootAvailable()
            else -> false
        }
    }

    override suspend fun downloadAndInstall(context: android.content.Context, downloadUrl: String) {
        val cachedApk = UpdateState.cachedApkFile.value
        val apkFile = if (cachedApk != null && cachedApk.exists() && cachedApk.length() > 0) {
            cachedApk
        } else {
            ApkDownloader().use { it.download(downloadUrl) }
        }
        ApkInstaller.install(context, apkFile)
    }
}
