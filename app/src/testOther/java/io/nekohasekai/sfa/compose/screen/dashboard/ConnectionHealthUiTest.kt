package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.connectionHealthText
import io.nekohasekai.sfa.compose.theme.SFATheme
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.health.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose controls with fictional evidence; does not start a VPN or load libbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1200dp-xhdpi")
class ConnectionHealthUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "+ru-rRU") fun recoveringKeepsDisconnectControl() {
        var disconnected = false
        val text = connectionHealthText(Status.Started,
            HealthSnapshot(assessment = HealthAssessment(HealthState.Recovering)))
        compose.setContent { SFATheme { Column {
            HowlConnectButton(Status.Started, { disconnected = true }, healthVerified = text.verified)
            HowlConnectionStatus(text)
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.health_recovering)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.health_recovering_hint)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.stop)).performClick()
        assertTrue(disconnected)
    }

    @Test fun expiredSuccessShowsUnverifiedExplanation() {
        val text = connectionHealthText(Status.Started,
            HealthSnapshot(assessment = HealthAssessment(HealthState.Unverified, HealthCause.EvidenceExpired)))
        compose.setContent { SFATheme { HowlConnectionStatus(text) } }
        compose.onNodeWithText(compose.activity.getString(R.string.health_unverified)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.health_expired_hint)).assertIsDisplayed()
    }
}
