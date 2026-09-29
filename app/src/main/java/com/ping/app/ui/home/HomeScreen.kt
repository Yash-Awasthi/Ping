package com.ping.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.theme.BrandBrush
import com.ping.app.ui.theme.PingCyan

@Composable
fun HomeScreen(
    onShareClick: () -> Unit,
    onRoomClick: () -> Unit,
    onPracticeClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val contactCount by viewModel.contactCount.collectAsStateWithLifecycle()

    val canShare = profile?.toShareableMap()?.isNotEmpty() == true

    val greeting = profile?.displayName?.takeIf { it.isNotBlank() }
    val greetingText = if (greeting != null) {
        stringResource(R.string.home_greeting, greeting)
    } else {
        stringResource(R.string.home_greeting_no_profile)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = greetingText,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.home_contact_count, contactCount),
            style = MaterialTheme.typography.labelMedium,
            color = PingCyan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ShareOrb(enabled = canShare, onClick = onShareClick)
        }

        Text(
            text = stringResource(
                if (canShare) R.string.home_share_hint else R.string.home_share_needs_profile,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(20.dp))

        androidx.compose.material3.TextButton(onClick = onPracticeClick) {
            Text(stringResource(R.string.home_practice_button), color = PingCyan)
        }

        PingCard(modifier = Modifier.clickable(role = Role.Button, onClick = onRoomClick)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_room_button),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_room_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The big Share button, with sonar rings that pulse outward while it is available. */
@Composable
private fun ShareOrb(enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "sonar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val disabledFill = MaterialTheme.colorScheme.surfaceVariant
    val onFill = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
        if (enabled) {
            repeat(3) { i ->
                val p = (phase + i / 3f) % 1f
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .scale(1f + p * 0.45f)
                        .alpha((1f - p) * 0.55f)
                        .border(2.dp, PingCyan, CircleShape),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(210.dp)
                .clip(CircleShape)
                .background(if (enabled) BrandBrush else SolidColor(disabledFill))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Share, contentDescription = null, tint = onFill, modifier = Modifier.size(36.dp))
                Text(
                    text = stringResource(R.string.home_share_button),
                    fontSize = 30.sp,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onFill,
                )
            }
        }
    }
}
