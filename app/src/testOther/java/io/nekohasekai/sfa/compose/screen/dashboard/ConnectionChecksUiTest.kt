package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.SFATheme
import io.nekohasekai.sfa.connections.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draw the real Compose controls/rows with fictional state. Never loads libbox or a VPN service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1200dp-xhdpi")
class ConnectionChecksUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun runningHasProgressAndStopOnly() {
        var stopped = false
        val snapshot = CheckSnapshot(mapOf("a" to CheckResult("a", CheckState.Working, 180, 1, 123),
            "b" to CheckResult("b", CheckState.RetryWaiting)), setOf("a", "b"))
        compose.setContent { SFATheme { ConnectionCheckControls(snapshot, CheckPhase.Running, true, {}, { stopped = true }) } }
        compose.onNodeWithTag("checks-progress").assertIsDisplayed()
        compose.onNodeWithTag("checks-stop").performClick()
        assertTrue(stopped)
        compose.onNodeWithTag("checks-retry").assertDoesNotExist()
        compose.onNodeWithTag("checks-start").assertDoesNotExist()
    }
    @Test fun stoppedShowsRetryWithoutRecheckingSuccessfulRows() {
        var retry: Boolean? = null
        val snapshot = CheckSnapshot(mapOf("a" to CheckResult("a", CheckState.Working, 180, 1, 123),
            "b" to CheckResult("b", CheckState.Cancelled)), setOf("a", "b"))
        compose.setContent { SFATheme { Column {
            ConnectionCheckControls(snapshot, CheckPhase.Stopped, true, { retry = it }, {})
            ConnectionCheckRow("North", "VLESS", snapshot.results["a"], true, true, true, {})
        } } }
        compose.onNodeWithTag("checks-retry").performClick(); assertEquals(true, retry)
        compose.onNodeWithText(compose.activity.getString(R.string.checks_working, 180)).assertIsDisplayed()
        compose.onNodeWithTag("checks-progress").assertDoesNotExist()
    }
    @Test fun networkChangeRequiresFullCheck() {
        var retry: Boolean? = null
        compose.setContent { SFATheme { ConnectionCheckControls(CheckSnapshot(), CheckPhase.NetworkChanged, true, { retry = it }, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.checks_network_changed)).assertIsDisplayed()
        compose.onNodeWithTag("checks-retry").assertDoesNotExist()
        compose.onNodeWithTag("checks-start").performClick(); assertEquals(false, retry)
    }
    @Test fun noResponseRemainsSelectableAndDoesNotClaimBlocking() {
        var selected = false
        compose.setContent { SFATheme {
            ConnectionCheckRow("North · Hysteria2", "Hysteria2", CheckResult("a", CheckState.NoResponse, attempt = 2, checkedAt = 123),
                true, false, true, { selected = true })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.checks_no_response_hint)).assertIsDisplayed()
        compose.onNodeWithText("North · Hysteria2").performClick(); assertTrue(selected)
    }
    @Test @Config(qualifiers = "+ru-rRU") fun russianErrorHasHonestExplanation() {
        compose.setContent { SFATheme {
            ConnectionCheckRow("North", "VLESS", CheckResult("a", CheckState.Error, checkedAt = 123), true, false, true, {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.checks_error)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.checks_error_hint)).assertIsDisplayed()
    }
    @Test @Config(qualifiers = "+de") fun germanEmptyListAndUnavailableStartAreExplicit() {
        compose.setContent { SFATheme { ConnectionCheckControls(CheckSnapshot(), CheckPhase.Done, false, {}, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.checks_empty)).assertIsDisplayed()
        compose.onNodeWithTag("checks-start").assertIsNotEnabled()
    }
}
