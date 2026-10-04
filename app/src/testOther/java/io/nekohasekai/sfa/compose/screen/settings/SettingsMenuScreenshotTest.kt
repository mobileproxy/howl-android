package io.nekohasekai.sfa.compose.screen.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.nekohasekai.sfa.compose.theme.SFATheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Скриншоты меню настроек без телефона: Robolectric рисует тот же Compose, что и в приложении,
 * PNG складываются в app/build/screenshots и уходят артефактом CI (howl-screenshots). Так меню
 * сверяется с Windows-клиентом до выпуска — собрать и запустить Android на этой машине нечем.
 *
 * Своё приложение (io.nekohasekai.sfa.Application) не поднимаем: оно грузит ядро, а меню
 * ничего из ядра не нужно — всё приходит в HowlMenuState.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1800dp-xhdpi")
class SettingsMenuScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val notes =
        "OpenConnect и Hysteria2 снова участвуют в автоподборе: замеры серверов обновляются " +
            "после смены сети и раз в 15 минут. Автопочинка уходит на другой сервер, а не на соседний протокол."

    private fun state(update: HowlUpdateRowState, backgroundOk: Boolean = true) = HowlMenuState(
        watchdog = true,
        autostartOnBoot = false,
        autoconnectOnOpen = true,
        backgroundOk = backgroundOk,
        russia = true,
        splitSummary = "sberbank.ru, tsum.ru +11",
        appsSummary = "Включено · приложений: 7",
        dnsSummary = "Автоматически (рекомендуется)",
        subscriptionSummary = "Последнее обновление: 04.10 21:36",
        languages = listOf("ru" to "Русский", "en" to "English", "de" to "Deutsch"),
        languageTag = "ru",
        update = update,
        showPrivilege = false,
    )

    private fun shot(name: String, state: HowlMenuState) {
        compose.setContent {
            SFATheme {
                Box(Modifier.fillMaxSize().background(HowlMenuColors.Ink)) {
                    HowlSettingsMenu(state, HowlMenuActions())
                }
            }
        }
        compose.waitForIdle()
        // captureToImage под Robolectric ждёт кадр, который не приходит (таймаут в forceRedraw,
        // CI 116) — рисуем корневой View сами, как это делает Roborazzi.
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "+ru")
    fun menuRu() = shot("settings_ru", state(HowlUpdateRowState(versionText = "Howl для Android 1.0.0 (816)")))

    @Test
    @Config(qualifiers = "+ru")
    fun menuUpdateRu() = shot(
        "settings_update_ru",
        state(
            HowlUpdateRowState(
                versionText = "Howl для Android 1.0.0 (816)",
                availableVersion = "1.0.0 (817)",
                availableSizeMb = 32,
                notes = notes,
            ),
            backgroundOk = false,
        ),
    )

    @Test
    @Config(qualifiers = "+de")
    fun menuUpdateDe() = shot(
        "settings_update_de",
        state(
            HowlUpdateRowState(
                versionText = "Howl für Android 1.0.0 (816)",
                availableVersion = "1.0.0 (817)",
                availableSizeMb = 32,
                notes = notes,
            ),
        ),
    )
}
