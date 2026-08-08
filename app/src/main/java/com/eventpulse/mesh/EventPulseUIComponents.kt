package com.eventpulse.mesh

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Color Palette ─────────────────────────────────────────────────────
object EventPulseColors {
    val densityLow = Color(0xFF4CAF50)
    val densityMedium = Color(0xFFFFC107)
    val densityPacked = Color(0xFFE53935)
    val densityUnknown = Color(0xFF9E9E9E)
    val verifiedBadge = Color(0xFF2196F3)
    val pulseGlow = Color(0xFF00BCD4)
}

// ─── Crowd Pulse Ring ──────────────────────────────────────────────────

/**
 * PERF: When density=UNKNOWN, the InfiniteTransition is never created,
 * so zero animation overhead. Only animates when there's live data.
 */
@Composable
fun CrowdPulseRing(
    density: CrowdDensityCalculator.VenueDensity,
    peerCount: Int,
    modifier: Modifier = Modifier
) {
    val densityColor = when (density) {
        CrowdDensityCalculator.VenueDensity.LOW -> EventPulseColors.densityLow
        CrowdDensityCalculator.VenueDensity.MEDIUM -> EventPulseColors.densityMedium
        CrowdDensityCalculator.VenueDensity.PACKED -> EventPulseColors.densityPacked
        CrowdDensityCalculator.VenueDensity.UNKNOWN -> EventPulseColors.densityUnknown
    }

    val isActive = density != CrowdDensityCalculator.VenueDensity.UNKNOWN

    // Only create InfiniteTransition when there's live data
    val pulseScale by if (isActive) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 1.15f,
            infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulseScale")
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val glowAlpha by if (isActive) {
        val t = rememberInfiniteTransition(label = "glow")
        t.animateFloat(0.3f, 0.7f,
            infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowAlpha")
    } else {
        remember { mutableFloatStateOf(0.15f) }
    }

    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).scale(pulseScale).clip(CircleShape)
            .background(densityColor.copy(alpha = glowAlpha * 0.3f)))
        Box(Modifier.size(36.dp).border(2.dp, densityColor.copy(alpha = 0.8f), CircleShape)
            .clip(CircleShape).background(densityColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Text(if (peerCount > 0) "$peerCount" else density.emoji,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = densityColor)
        }
    }
}

// ─── Channel Tab Navigation ────────────────────────────────────────────

enum class EventChannel(val displayName: String, val description: String) {
    ANNOUNCEMENTS("Announcements", "📢"),
    QA("Q&A", "❓"),
    GENERAL("General", "💬");

    companion object {
        fun fromJsonName(name: String): EventChannel = when (name.lowercase()) {
            "announcements" -> ANNOUNCEMENTS
            "qa", "q&a" -> QA
            else -> GENERAL
        }
    }
}

@Composable
fun EventChannelTabs(
    selectedChannel: EventChannel,
    onChannelSelected: (EventChannel) -> Unit,
    unreadCounts: Map<EventChannel, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), color = colorScheme.surfaceVariant.copy(alpha = 0.5f), tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EventChannel.entries.forEach { channel ->
                val isSelected = channel == selectedChannel
                val bgColor by animateColorAsState(if (isSelected) colorScheme.primaryContainer else Color.Transparent, tween(300), label = "tabBg")
                val textColor by animateColorAsState(if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant, tween(300), label = "tabText")
                Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(bgColor)
                    .clickable { onChannelSelected(channel) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.description, fontSize = 14.sp)
                        Text(channel.displayName, fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = textColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val unread = unreadCounts[channel] ?: 0
                        if (unread > 0) Badge(containerColor = colorScheme.error, contentColor = colorScheme.onError) { Text("$unread", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

// ─── Verified Message Card ─────────────────────────────────────────────

/**
 * PERF: Uses derivedStateOf for channel colors — recomputes only when channel or colorScheme changes.
 */
@Composable
fun EventPulseMessageCard(
    sender: String,
    channel: String,
    body: String,
    timestamp: Long,
    isVerified: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val channelColor by remember { derivedStateOf {
        when (channel.lowercase()) {
            "announcements" -> Color(0xFF1976D2)
            "qa", "q&a" -> Color(0xFF388E3C)
            else -> colorScheme.secondary
        }
    } }

    val bgColor = colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = bgColor, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sender, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.primary)
                if (isVerified) Surface(shape = CircleShape, color = EventPulseColors.verifiedBadge, modifier = Modifier.size(14.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("✓", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = channelColor.copy(alpha = 0.15f)) {
                    Text("#$channel", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = channelColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text(body, fontSize = 15.sp, color = colorScheme.onSurface, maxLines = 10, overflow = TextOverflow.Ellipsis)
            Text(formatEventPulseTimestamp(timestamp), fontSize = 10.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.End))
        }
    }
}

private fun formatEventPulseTimestamp(unixSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(unixSeconds * 1000L))
}
