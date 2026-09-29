package com.ping.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connection state for the indicator.
 */
enum class ConnectionState {
    /** Scanning for nearby devices via BLE. */
    SCANNING,
    /** Attempting to connect to a matched peer. */
    CONNECTING,
    /** Successfully paired and ready for exchange. */
    PAIRED,
    /** Connection failed or lost. */
    ERROR,
    /** Idle — not actively scanning. */
    IDLE,
}

private val ScanningBlue = Color(0xFF2196F3)
private val ConnectingAmber = Color(0xFFFFA000)
private val PairedGreen = Color(0xFF4CAF50)
private val ErrorRed = Color(0xFFF44336)
private val IdleGray = Color(0xFF9E9E9E)

private val stateColors = mapOf(
    ConnectionState.SCANNING to ScanningBlue,
    ConnectionState.CONNECTING to ConnectingAmber,
    ConnectionState.PAIRED to PairedGreen,
    ConnectionState.ERROR to ErrorRed,
    ConnectionState.IDLE to IdleGray,
)

private val stateLabels = mapOf(
    ConnectionState.SCANNING to "Scanning…",
    ConnectionState.CONNECTING to "Connecting…",
    ConnectionState.PAIRED to "Paired",
    ConnectionState.ERROR to "Error",
    ConnectionState.IDLE to "Idle",
)

/**
 * Animated connection status indicator.
 *
 * Shows a pulsing circle for scanning, spinning border for connecting,
 * solid green check for paired, and red X for error.
 *
 * @param state Current connection state
 * @param modifier Layout modifier
 * @param size Indicator diameter (default 48dp)
 */
@Composable
fun ConnectionStatusIndicator(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    size: Int = 48,
) {
    val targetColor = stateColors[state] ?: IdleGray
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 400),
        label = "statusColor",
    )

    // Pulse animation for SCANNING
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Rotation for CONNECTING
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
        ),
        label = "rotation",
    )

    val scale = when (state) {
        ConnectionState.SCANNING -> pulseScale
        else -> 1f
    }

    val borderWidth = when (state) {
        ConnectionState.CONNECTING -> 3.dp
        else -> 2.dp
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size((size + 16).dp),
    ) {
        // Outer ring — rotating for CONNECTING
        Box(
            modifier = Modifier
                .size(size.dp)
                .scale(scale)
                .clip(CircleShape)
                .border(
                    width = borderWidth,
                    color = animatedColor.copy(alpha = 0.6f),
                    shape = CircleShape,
                )
                .then(
                    if (state == ConnectionState.CONNECTING) {
                        Modifier.alpha(1f)
                    } else Modifier
                ),
        )

        // Inner circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size((size - 8).dp)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = 0.15f))
                .border(
                    width = 1.5.dp,
                    color = animatedColor,
                    shape = CircleShape,
                ),
        ) {
            Text(
                text = when (state) {
                    ConnectionState.SCANNING -> "📡"
                    ConnectionState.CONNECTING -> "⏳"
                    ConnectionState.PAIRED -> "✓"
                    ConnectionState.ERROR -> "✕"
                    ConnectionState.IDLE -> "—"
                },
                fontSize = (size / 3).sp,
                color = animatedColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        // Label below
        Text(
            text = stateLabels[state] ?: "",
            fontSize = 10.sp,
            color = animatedColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (size / 2 + 4).dp),
        )
    }
}

/**
 * Compact inline status badge — smaller version for list items.
 */
@Composable
fun ConnectionStatusBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val color = stateColors[state] ?: IdleGray
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "badgeColor",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedColor),
        )
        Text(
            text = stateLabels[state] ?: "",
            fontSize = 12.sp,
            color = animatedColor,
            fontWeight = FontWeight.Medium,
        )
    }
}
