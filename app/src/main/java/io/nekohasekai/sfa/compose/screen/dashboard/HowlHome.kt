package io.nekohasekai.sfa.compose.screen.dashboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

/**
 * Big central connect / disconnect button — the hero of the Howl home screen.
 *
 * Purely status-driven visuals: a mint-filled disc with a soft glow when connected,
 * a mint-outlined disc when idle, and a progress ring while transitioning. The actual
 * decision of what a tap does lives in the caller (connect, disconnect, or add a server).
 */
@Composable
fun HowlConnectButton(status: Status, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mint = MaterialTheme.colorScheme.primary
    val connected = status == Status.Started
    val transitioning = status == Status.Starting || status == Status.Stopping

    // Gentle breathing glow while connected.
    val infinite = rememberInfiniteTransition(label = "connectGlow")
    val glowScale by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowScale",
    )

    Box(
        modifier = modifier.size(248.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft radial glow behind the disc when connected.
        if (connected) {
            Box(
                modifier = Modifier
                    .size(248.dp)
                    .scale(glowScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(mint.copy(alpha = 0.38f), Color.Transparent),
                        ),
                        shape = CircleShape,
                    ),
            )
        }

        // Progress ring while starting / stopping.
        if (transitioning) {
            CircularProgressIndicator(
                modifier = Modifier.size(210.dp),
                color = mint,
                strokeWidth = 3.dp,
            )
        }

        val discColor = when {
            connected -> mint
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val iconTint = when {
            connected -> MaterialTheme.colorScheme.onPrimary
            transitioning -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> mint
        }

        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(discColor)
                .then(
                    if (connected) {
                        Modifier
                    } else {
                        Modifier.border(
                            width = 2.dp,
                            color = mint.copy(alpha = if (transitioning) 0.25f else 0.7f),
                            shape = CircleShape,
                        )
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(84.dp),
            )
        }
    }
}

/**
 * Bottom "current server" chip. Shows the selected server name, or a call-to-action to
 * add a subscription when there is none. Tapping opens the server picker (caller-provided).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowlServerSelector(serverName: String?, onClick: () -> Unit, label: String, emptyTitle: String, emptyHint: String, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (serverName != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = emptyTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Просьба разрешить работу в фоне.
 *
 * Без этого разрешения Android (особенно Xiaomi, Huawei, Oppo) усыпляет VPN-службу, и связь
 * рвётся сама собой. Настройка есть в разделе «Работа в фоне», но новый пользователь туда не
 * заходит и о проблеме не знает — поэтому просим прямо на главной, один раз и одним нажатием.
 *
 * Показывается, только если разрешения действительно нет; после выдачи исчезает сама.
 */
@Composable
fun BackgroundPermissionBanner(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isGranted(): Boolean {
        val manager = context.getSystemService(PowerManager::class.java)
        return manager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    var granted by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = isGranted() }

    // Перепроверяем при возврате в приложение: разрешение выдаётся в системном диалоге,
    // и результат может прийти уже после того, как экран снова оказался на виду.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.BatteryAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.howl_background_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.howl_background_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    launcher.launch(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.howl_background_banner_action))
            }
        }
    }
}
