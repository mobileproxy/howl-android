package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.SFATheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1200dp-xhdpi")
class SoftSwitchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun pendingSelectionExplainsPreservedFlowsAndCanBeCancelled() {
        var cancelled = false
        compose.setContent { SFATheme { SoftSwitchNotice(true) { cancelled = true } } }
        compose.onNodeWithText(compose.activity.getString(R.string.soft_switch_hint)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.soft_switch_checking)).assertIsDisplayed()
        compose.onNodeWithTag("soft-switch-cancel").performClick()
        assertTrue(cancelled)
    }
    @Test @Config(qualifiers = "+ru-rRU") fun idleRussianNoticeDoesNotPretendThatAllConnectionsMoved() {
        compose.setContent { SFATheme { SoftSwitchNotice(false) {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.soft_switch_hint)).assertIsDisplayed()
        compose.onNodeWithTag("soft-switch-progress").assertDoesNotExist()
        compose.onNodeWithTag("soft-switch-cancel").assertDoesNotExist()
    }
}
