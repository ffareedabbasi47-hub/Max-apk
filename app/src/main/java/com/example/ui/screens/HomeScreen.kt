package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MaxState
import com.example.ui.components.ArcReactorView
import com.example.ui.components.EdgeGlowOverlay
import com.example.ui.theme.*
import com.example.ui.viewmodel.MaxViewModel

// UI REDESIGN: Home is now voice-only, per request -- the quick-action
// shortcut grid, the on-screen conversation/chat stream, the full-history
// dialog, and the text-input+send row have all been removed from this
// screen. Everything happens by saying "Max" or tapping the core; the only
// text on screen is MAX's own spoken line, shown as live captioning, not a
// chat log. Other screens (Control / Vision / Tools / Settings) are
// untouched.
@Composable
fun HomeScreen(
    viewModel: MaxViewModel,
    modifier: Modifier = Modifier
) {
    val maxState by viewModel.maxState.collectAsState()
    val lastSpeechText by viewModel.lastSpeechText.collectAsState()
    val isFallbackActive by viewModel.isFallbackActive.collectAsState()
    val fallbackNotice by viewModel.fallbackNotice.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val liveTranscript by viewModel.liveTranscript.collectAsState()

    val stateColor = when (maxState) {
        MaxState.IDLE -> NeonGreenStatus
        MaxState.WAKE_DETECTED -> Color(0xFF66FFC2)
        MaxState.LISTENING -> CyanPrimary
        MaxState.PROCESSING -> NeonAmberAlert
        MaxState.EXECUTING -> Color(0xFF00E5FF)
        MaxState.SPEAKING -> CyanTertiary
        MaxState.READY -> NeonGreenStatus
        MaxState.ERROR -> Color(0xFFFF5252)
    }

    val statusLine = when (maxState) {
        MaxState.IDLE -> "Say \"Max\" or tap the core"
        MaxState.WAKE_DETECTED -> "Activating..."
        MaxState.LISTENING -> "Listening..."
        MaxState.PROCESSING -> "Thinking..."
        MaxState.EXECUTING -> "On it, Boss..."
        MaxState.SPEAKING -> "Speaking..."
        MaxState.READY -> "Ready."
        MaxState.ERROR -> "Error — try again"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF071A2B), HudBackground, Color.Black),
                    radius = 900f
                )
            )
    ) {
        // PHASE 11 — screen-edge glow, reacts live to MaxState. Purely a
        // Compose overlay (see EdgeGlowOverlay for the honesty note on
        // what this can and can't claim to be).
        EdgeGlowOverlay(
            maxState = maxState,
            modifier = Modifier.fillMaxSize()
        )

        Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Hero Core -- the entire interface, essentially
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            ArcReactorView(
                maxState = maxState,
                onClick = { viewModel.toggleVoiceListening() },
                modifier = Modifier.size(330.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Live status pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(HudSurface, shape = RoundedCornerShape(20.dp))
                .border(1.dp, stateColor.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(stateColor, shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusLine,
                color = stateColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // PHASE 18 — live transcript while the user is mid-sentence. Real
        // partial-recognition text, not a placeholder -- disappears the
        // moment listening stops or the sentence is finalized.
        AnimatedVisibility(
            visible = maxState == MaxState.LISTENING && liveTranscript.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent, shape = RoundedCornerShape(14.dp))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.4f), shape = RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "\"$liveTranscript\"",
                    color = CyanPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Start
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // MAX's spoken line -- live captioning, not a chat log
        AnimatedVisibility(visible = lastSpeechText.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudSurface, shape = RoundedCornerShape(14.dp))
                    .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speaker",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = lastSpeechText,
                        color = TextCyanLight,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (isFallbackActive && fallbackNotice.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = NeonAmberAlert.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmberAlert),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = fallbackNotice,
                    color = NeonAmberAlert,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // ACTION ORCHESTRATOR — CONFIRMATION CARD
        // Appears only when a sensitive action (WhatsApp send, phone call)
        // is genuinely being held for approval -- it is never shown for,
        // and never causes, an action that hasn't actually been proposed.
        AnimatedVisibility(visible = pendingConfirmation != null, enter = fadeIn(), exit = fadeOut()) {
            val confirmation = pendingConfirmation
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HudSurface, shape = RoundedCornerShape(14.dp))
                        .border(1.dp, NeonAmberAlert, shape = RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "CONFIRM ACTION",
                            color = NeonAmberAlert,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = confirmation?.confirmationSpeech ?: "",
                            color = TextCyanLight,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(NeonGreenStatus, shape = RoundedCornerShape(10.dp))
                                    .clickable { viewModel.confirmPendingAction() }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CONFIRM", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color.Transparent, shape = RoundedCornerShape(10.dp))
                                    .border(1.dp, NeonRedError, shape = RoundedCornerShape(10.dp))
                                    .clickable { viewModel.cancelPendingAction() }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CANCEL", color = NeonRedError, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Stop control -- only surfaces while MAX is actively talking/listening
        AnimatedVisibility(
            visible = maxState == MaxState.SPEAKING || maxState == MaxState.LISTENING,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .background(NeonRedError, shape = RoundedCornerShape(24.dp))
                    .clickable { viewModel.stopAllAudioAndListening() }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        }
    }
}
