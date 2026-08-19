package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.api.diagnostics.GeminiDiagnosticResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MaxViewModel

@Composable
fun SettingsScreen(
    viewModel: MaxViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()


    var pitch by remember { mutableFloatStateOf(0.85f) }
    var speed by remember { mutableFloatStateOf(1.05f) }
    var wakeWordEnabled by remember { mutableStateOf(true) }
    var autoReplyEnabled by remember { mutableStateOf(true) }

    val hasGeminiKey = BuildConfig.GEMINI_API_KEY.isNotBlank() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "MAX ARCHITECTURE CONFIGURATION",
            color = CyanPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // SETTINGS REDESIGN: the old flat "MULTI-PROVIDER API KEY MANAGEMENT" box
        // (six unmasked plaintext fields, no per-provider status, no real test)
        // is replaced by AiProvidersSection.kt -- masked keys with show/hide,
        // silent Save, and a real per-provider [TEST CONNECTION].
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            AiProvidersSection(viewModel = viewModel)
        }

        // PHASE 21 — CENTRALIZED PERMISSION MANAGER PANEL. Real, live status
        // for every permission MAX actually uses -- each with a plain-language
        // reason and, for runtime permissions, a working request button; for
        // special (Accessibility/Notification) access, a link to the exact
        // Settings screen since Android provides no direct-request API for those.
        PermissionManagerPanel()

        // Accessibility Service Automation Onboarding Card
        val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, if (isAccessibilityEnabled) NeonGreenStatus else NeonAmberAlert, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AUTOMATION ACCESSIBILITY SERVICE",
                        color = CyanPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Surface(
                        color = if (isAccessibilityEnabled) NeonGreenStatus.copy(alpha = 0.2f) else NeonAmberAlert.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isAccessibilityEnabled) NeonGreenStatus else NeonAmberAlert),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityEnabled) "ACTIVE" else "DISABLED",
                            color = if (isAccessibilityEnabled) NeonGreenStatus else NeonAmberAlert,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Required for hands-free screen reading, button clicking, and UI automation. Without this service, tap automation commands will fail.",
                    color = TextCyanMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (!isAccessibilityEnabled) {
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonAmberAlert, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ENABLE ACCESSIBILITY SERVICE IN SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Gemini's detailed diagnostic (status code / latency / raw response) is
        // now folded into the AI PROVIDERS card above via its [TEST CONNECTION]
        // button, so the old standalone panel is no longer shown here.

        LocalModelCard(viewModel = viewModel)

        VoskWakeWordCard(viewModel = viewModel)

        // Voice Engine Voice Parameters
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = "JARVIS VOICE SYNTHESIS CONTROL",
                    color = CyanPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Voice Pitch (Masculine AI Tone): ${"%.2f".format(pitch)}",
                    color = TextCyanMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = pitch,
                    onValueChange = {
                        pitch = it
                        viewModel.voiceEngine.setVoiceParams(pitch, speed)
                    },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Articulation Rate: ${"%.2f".format(speed)}x",
                    color = TextCyanMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        viewModel.voiceEngine.setVoiceParams(pitch, speed)
                    },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "TTS Language & Accent Focus:",
                    color = TextCyanMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                val selectedLang by viewModel.voiceEngine.selectedLanguage.collectAsStateWithLifecycle()
                val langOptions = listOf(
                    "AUTO" to "Auto Hinglish",
                    "hi_IN" to "Hindi (hi-IN)",
                    "en_IN" to "Indian English (en-IN)",
                    "en_US" to "US English (en-US)"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    langOptions.forEach { (code, label) ->
                        val isSelected = selectedLang == code
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.voiceEngine.setLanguagePreference(code) },
                            label = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary,
                                selectedLabelColor = Color.Black,
                                containerColor = HudSurfaceVariant,
                                labelColor = TextCyanLight
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = { viewModel.voiceEngine.speak("Haan Boss! Main Hinglish aur English dono samajhta aur bolta hoon.") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanSecondary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Test Voice Output", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Wake Word Settings Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "WAKE WORD PIPELINE ('MAX')",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Status: ${if (wakeWordEnabled) "ACTIVE (Listening in background)" else "PAUSED"}",
                            color = if (wakeWordEnabled) NeonGreenStatus else NeonAmberAlert,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = wakeWordEnabled,
                        onCheckedChange = {
                            wakeWordEnabled = it
                            viewModel.toggleBackgroundWakeService(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                    )

                }

                Text(
                    text = "When enabled, saying 'Max' or 'Hey Max' will trigger 'Yes, Boss?' and listen for your query.",
                    color = TextCyanMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                Button(
                    onClick = { viewModel.testWakeWord() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "TEST WAKE WORD ('MAX')", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }


        // Voice Feedback & Battery Mode Card
        // Phase 8/9 fix: previously neither toggle existed anywhere in the
        // app -- MAX always spoke, and there was no Low Power/Standard
        // distinction despite the spec calling both out explicitly.
        var voiceFeedbackEnabled by remember {
            mutableStateOf(com.example.data.settings.MaxPreferences.isVoiceFeedbackEnabled(context))
        }
        var lowPowerMode by remember {
            mutableStateOf(com.example.data.settings.MaxPreferences.isLowPowerMode(context))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "VOICE FEEDBACK",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "MAX replies still appear as text either way. Default: OFF.",
                            color = TextCyanMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = voiceFeedbackEnabled,
                        onCheckedChange = {
                            voiceFeedbackEnabled = it
                            com.example.data.settings.MaxPreferences.setVoiceFeedbackEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BATTERY MODE",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (lowPowerMode) "Low Power — slower background wake restarts"
                                   else "Standard — fastest wake responsiveness",
                            color = TextCyanMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = !lowPowerMode,
                        onCheckedChange = {
                            lowPowerMode = !it
                            com.example.data.settings.MaxPreferences.setLowPowerMode(context, !it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                    )
                }
            }
        }

        // Action Buttons
        Button(
            onClick = { viewModel.clearHistory() },
            colors = ButtonDefaults.buttonColors(containerColor = NeonRedError, contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "CLEAR ALL COMMAND LOGS", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun GeminiDiagnosticCard(
    viewModel: MaxViewModel
) {
    val diagnosticResult by viewModel.geminiDiagnosticResult.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface, shape = RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (diagnosticResult?.isSuccess == true) NeonGreenStatus else if (diagnosticResult != null) NeonAmberAlert else HudBorderCyan,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GEMINI API DIAGNOSTIC SERVICE",
                    color = CyanPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                if (diagnosticResult != null) {
                    val badgeColor = if (diagnosticResult!!.isSuccess) NeonGreenStatus else NeonAmberAlert
                    Surface(
                        color = badgeColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = diagnosticResult!!.statusCategory,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = "Verifies real connectivity & responsiveness of Gemini using your saved keys (Slot 1/2/3 or Custom Key). Logs specific error codes on failure rather than falling back to hardcoded responses.",
                color = TextCyanMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = {
                    // BUGFIX: this used to always test BuildConfig.GEMINI_API_KEY (the
                    // developer's placeholder default), completely ignoring whatever
                    // key the user actually typed into the Settings fields — so the
                    // test could fail forever even with a perfectly valid user key.
                    val userKey = listOf(
                        viewModel.getApiKeySlot(1),
                        viewModel.getApiKeySlot(2),
                        viewModel.getApiKeySlot(3),
                        viewModel.getCustomKey("custom_gemini_api_key")
                    ).firstOrNull { it.isNotBlank() }
                    if (userKey != null) {
                        viewModel.runGeminiDiagnosticCheck(userKey)
                    } else {
                        viewModel.runGeminiDiagnosticCheck()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "PING GEMINI API (RUN DIAGNOSTIC)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            diagnosticResult?.let { res ->
                HorizontalDivider(color = HudBorderCyan.copy(alpha = 0.5f), thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Status Code: ${res.statusCode ?: "N/A"}",
                        color = if (res.isSuccess) NeonGreenStatus else NeonAmberAlert,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Latency: ${res.latencyMs}ms | Model: ${res.modelTested}",
                        color = TextCyanLight,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Key Source: ${res.apiKeySource}",
                        color = TextCyanMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    if (!res.errorMessage.isNullOrBlank()) {
                        Text(
                            text = "Error Log Details:\n${res.errorMessage}",
                            color = Color(0xFFFF6B6B),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        )
                    } else if (!res.rawResponseBody.isNullOrBlank()) {
                        Text(
                            text = "Response Preview:\n${res.rawResponseBody.take(150)}...",
                            color = TextCyanMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lets the user import ANY compatible local model (.task format, produced by
 * MediaPipe's LLM conversion tool or downloaded pre-converted from Kaggle
 * Models) and run it fully offline, no internet or API key required.
 */
@Composable
fun LocalModelCard(
    viewModel: MaxViewModel
) {
    val models by viewModel.localModels.collectAsStateWithLifecycle()
    val importStatus by viewModel.localModelImportStatus.collectAsStateWithLifecycle()
    val testStatus by viewModel.localModelTestStatus.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshLocalModels()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val displayName = "local_model_${System.currentTimeMillis()}.task"
            viewModel.importLocalModel(uri, displayName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface, shape = RoundedCornerShape(12.dp))
            .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "OFFLINE LOCAL LLM (RUNS WITHOUT INTERNET)",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Import any MediaPipe-compatible .task model (Gemma, Phi-3-mini, Falcon-RW, StableLM, or your own converted model). MAX will automatically use it when there's no internet, or as a backup if cloud providers fail.",
                color = TextCyanMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "IMPORT MODEL (.task FILE)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            importStatus?.let { status ->
                Text(
                    text = status,
                    color = TextCyanLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (models.any { it.isActive }) {
                Button(
                    onClick = { viewModel.testLocalModel() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HudSurface,
                        contentColor = CyanPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "TEST ACTIVE LOCAL MODEL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                testStatus?.let { status ->
                    Text(
                        text = status,
                        color = if (status.startsWith("✓")) NeonGreenStatus else NeonAmberAlert,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (models.isEmpty()) {
                Text(
                    text = "No local models imported yet.",
                    color = TextCyanMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (model.isActive) NeonGreenStatus.copy(alpha = 0.1f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.fileName,
                                color = if (model.isActive) NeonGreenStatus else TextCyanLight,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${model.sizeBytes / (1024 * 1024)} MB" + if (model.isActive) " • ACTIVE" else "",
                                color = TextCyanMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (!model.isActive) {
                            TextButton(onClick = { viewModel.setActiveLocalModel(model.fileName) }) {
                                Text("USE", color = CyanPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        TextButton(onClick = { viewModel.deleteLocalModel(model.fileName) }) {
                            Text("DELETE", color = NeonAmberAlert, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}


/**
 * Lets the user import a Vosk offline speech model for continuous,
 * non-flickering "Max" wake-word detection — free, fully offline, no
 * account/signup needed (unlike Porcupine). Download a small model
 * (~40MB) from https://alphacephei.com/vosk/models and import the .zip
 * here as-is; MAX extracts it automatically.
 */
@Composable
fun VoskWakeWordCard(
    viewModel: MaxViewModel
) {
    val status by viewModel.voskStatus.collectAsStateWithLifecycle()
    val isReady = viewModel.isVoskReady()

    val filePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importVoskModel(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface, shape = RoundedCornerShape(12.dp))
            .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "SEAMLESS WAKE WORD (FREE, OFFLINE, NO SIGNUP)",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Optional upgrade: continuous 'Max' listening with no visible mic on/off cycling. Download a small model (~40MB, no account needed) from alphacephei.com/vosk/models and import the .zip below as-is.",
                color = TextCyanMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = { filePicker.launch(arrayOf("application/zip", "*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("IMPORT VOSK MODEL (.zip)", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            status?.let {
                Text(it, color = if (it.startsWith("✓")) NeonGreenStatus else TextCyanLight, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            Text(
                text = if (isReady) "STATUS: ✓ Active — seamless mode in use" else "STATUS: Not configured — using default (flickering) mic mode",
                color = if (isReady) NeonGreenStatus else NeonAmberAlert,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PermissionManagerPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-evaluated on demand (open/close of the panel is enough churn to
    // re-check; permissions can only change while this app isn't in the
    // foreground focus, e.g. user backs out to Settings and returns).
    var refreshTick by remember { mutableStateOf(0) }
    val statuses = remember(refreshTick) {
        com.example.system.PermissionManager.ALL.associateWith {
            com.example.system.PermissionManager.isGranted(context, it)
        }
    }

    val runtimeToRequest = com.example.system.PermissionManager.ALL
        .filter { it.kind == com.example.system.PermissionManager.Kind.RUNTIME }
        .mapNotNull { it.androidPermission }
        .toTypedArray()

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    val specialSettingsLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refreshTick++ }

    val grantedCount = statuses.values.count { it }
    val total = statuses.size

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface, shape = RoundedCornerShape(12.dp))
            .border(1.dp, if (grantedCount == total) NeonGreenStatus else NeonAmberAlert, shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PERMISSION MANAGER",
                    color = CyanPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "$grantedCount / $total GRANTED",
                    color = if (grantedCount == total) NeonGreenStatus else NeonAmberAlert,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            statuses.forEach { (permission, granted) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = permission.label,
                            color = TextCyanLight,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (granted) {
                            Text("GRANTED", color = NeonGreenStatus, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        } else {
                            Button(
                                onClick = {
                                    if (permission.kind == com.example.system.PermissionManager.Kind.RUNTIME) {
                                        runtimeLauncher.launch(runtimeToRequest)
                                    } else {
                                        specialSettingsLauncher.launch(com.example.system.PermissionManager.settingsIntentFor(permission))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonAmberAlert, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("GRANT", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Text(
                        text = permission.rationale,
                        color = TextCyanMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
