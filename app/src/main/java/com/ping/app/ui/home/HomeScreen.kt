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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import android.content.Context
import com.ping.app.ui.components.PingCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.ui.components.ModeTile
import com.ping.app.ui.components.SectionLabel
import com.ping.app.ui.theme.BrandBrush
import com.ping.app.ui.theme.PingCyan
import com.ping.app.ui.theme.PingMagenta
import com.ping.app.ui.theme.SuccessGreen

@Composable
fun HomeScreen(
    onShareClick: () -> Unit,
    onRoomClick: () -> Unit,
    onPracticeClick: () -> Unit,
    onRollCallClick: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
            text = pluralStringResource(R.plurals.home_contact_count, contactCount, contactCount),
            style = MaterialTheme.typography.labelMedium,
            color = PingCyan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        OnboardingCard(onPracticeClick)

        Box(
            modifier = Modifier.fillMaxWidth(),
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

        SectionLabel(stringResource(R.string.home_more), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ModeTile(
                title = stringResource(R.string.home_room_button),
                hint = stringResource(R.string.home_room_hint),
                icon = Icons.AutoMirrored.Filled.Send,
                accent = PingCyan,
                onClick = onRoomClick,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = stringResource(R.string.home_roll_button),
                hint = stringResource(R.string.home_roll_hint),
                icon = Icons.Default.Face,
                accent = SuccessGreen,
                onClick = onRollCallClick,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = stringResource(R.string.home_practice_button),
                hint = stringResource(R.string.home_practice_hint),
                icon = Icons.Default.PlayArrow,
                accent = PingMagenta,
                onClick = onPracticeClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val ONBOARDING_PREFS = "onboarding"
private const val KEY_SEEN = "home_card_seen"

/** First-run how-to, shown until dismissed once. */
@Composable
private fun OnboardingCard(onPracticeClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean(KEY_SEEN, false)) }
    if (seen) return
    fun dismiss() {
        prefs.edit { putBoolean(KEY_SEEN, true) }
        seen = true
    }
    PingCard(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        listOf(R.string.onboarding_step_profile, R.string.onboarding_step_gesture, R.string.onboarding_step_hold)
            .forEachIndexed { i, step ->
                Text(
                    text = "${i + 1}. " + stringResource(step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { dismiss(); onPracticeClick() }) {
                Text(stringResource(R.string.onboarding_practice), color = PingCyan)
            }
            TextButton(onClick = ::dismiss) { Text(stringResource(R.string.onboarding_dismiss)) }
        }
    }
}

/** Three rings pulsing outward; the phase is read only while drawing, so they never recompose. */
@Composable
private fun SonarRings() {
    val phase = rememberInfiniteTransition(label = "sonar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    repeat(3) { i ->
        Box(
            modifier = Modifier
                .size(210.dp)
                .graphicsLayer {
                    val p = (phase.value + i / 3f) % 1f
                    scaleX = 1f + p * 0.45f
                    scaleY = scaleX
                    alpha = (1f - p) * 0.55f
                }
                .border(2.dp, PingCyan, CircleShape),
        )
    }
}

/** The big Share button, with sonar rings that pulse outward while it is available. */
@Composable
private fun ShareOrb(enabled: Boolean, onClick: () -> Unit) {
    val disabledFill = MaterialTheme.colorScheme.surfaceVariant
    val onFill = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
        if (enabled) SonarRings()
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
