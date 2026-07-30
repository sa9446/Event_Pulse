package com.eventpulse.mesh

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * EventPulse splash screen with an animated pulse-ring logo and tagline.
 *
 * Displays briefly during app initialization, then transitions to the
 * onboarding flow or main chat screen.
 */
@Composable
fun EventPulseSplashScreen(
    onSplashComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // Animated pulse rings
    val infiniteTransition = rememberInfiniteTransition(label = "splashPulse")
    val outerRingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRingAlpha"
    )
    val outerRingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRingScale"
    )
    val innerPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "innerPulse"
    )

    // Tagline fade-in
    var taglineVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        taglineVisible = true
    }

    // Auto-dismiss after splash duration
    LaunchedEffect(Unit) {
        delay(2500)
        onSplashComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to colorScheme.primary.copy(alpha = 0.08f),
                        0.5f to colorScheme.background,
                        1.0f to colorScheme.primary.copy(alpha = 0.04f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated logo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp)
            ) {
                // Outer ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(outerRingScale)
                        .alpha(outerRingAlpha)
                        .clip(CircleShape)
                        .background(colorScheme.primary.copy(alpha = 0.3f))
                )
                // Middle ring
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(innerPulse)
                        .clip(CircleShape)
                        .background(colorScheme.primary.copy(alpha = 0.15f))
                )
                // Inner core
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                colorStops = arrayOf(
                                    0.0f to colorScheme.primary,
                                    0.5f to colorScheme.secondary,
                                    1.0f to colorScheme.primary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "EP",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // App name
            Text(
                text = "EventPulse",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )

            // Tagline
            if (taglineVisible) {
                Text(
                    text = "Offline Venue Engagement",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .alpha(
                            remember { androidx.compose.animation.core.Animatable(0f) }
                                .also { animatable ->
                                    LaunchedEffect(Unit) {
                                        animatable.animateTo(
                                            1f,
                                            animationSpec = tween(800, easing = FastOutSlowInEasing)
                                        )
                                    }
                                }.value
                        )
                )
            }

            Spacer(Modifier.height(48.dp))

            // Loading dots
            val dotCount = 3
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(dotCount) { index ->
                    val delay = index * 300L
                    val dotAlpha by remember { mutableStateOf(0.3f) }
                    val transition = rememberInfiniteTransition(label = "dot$index")
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1200, delayMillis = delay.toInt()),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotAlpha$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}
