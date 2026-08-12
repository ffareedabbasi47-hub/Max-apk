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

        // Multi-API Key Rotation Panel
        var keySlot1 by remember { mutableStateOf(viewModel.getApiKeySlot(1)) }
        var keySlot2 by remember { mutableStateOf(viewModel.getApiKeySlot(2)) }
        var keySlot3 by remember { mutableStateOf(viewModel.getApiKeySlot(3)) }
        var customGeminiKey by remember { mutableStateOf(viewModel.getCustomKey("custom_gemini_api_key")) }
        var openAiKey by remember { mutableStateOf(viewModel.getCustomKey("openai_api_key")) }
        var claudeKey by remember { mutableStateOf(viewModel.getCustomKey("claude_api_key")) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudSurface, shape = RoundedCornerShape(12.dp))
                .border(1.dp, HudBorderCyan, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MULTI-PROVIDER API KEY MANAGEMENT",
                    color = CyanPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Configure Gemini, OpenAI, or Claude keys. All entered keys are saved locally in SharedPreferences.",
                    color = TextCyanMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = keySlot1,
                    onValueChange = {
                        keySlot1 = it
                        viewModel.saveApiKeySlot(1, it)
                    },
                    label = { Text("Gemini Slot 1 (Primary)", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )

                OutlinedTextField(
                    value = keySlot2,
                    onValueChange = {
                        keySlot2 = it
                        viewModel.saveApiKeySlot(2, it)
                    },
                    label = { Text("Gemini Slot 2 (Backup)", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )

                OutlinedTextField(
                    value = keySlot3,
                    onValueChange = {
                        keySlot3 = it
                        viewModel.saveApiKeySlot(3, it)
                    },
                    label = { Text("Gemini Slot 3 (Backup)", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )

                OutlinedTextField(
                    value = customGeminiKey,
                    onValueChange = {
                        customGeminiKey = it
                        viewModel.saveCustomKey("custom_gemini_api_key", it)
                    },
                    label = { Text("Custom Gemini API Key", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )

                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = {
                        openAiKey = it
                        viewModel.saveCustomKey("openai_api_key", it)
                    },
                    label = { Text("OpenAI API Key (Backup Provider)", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )

                OutlinedTextField(
                    value = claudeKey,
                    onValueChange = {
                        claudeKey = it
                        viewModel.saveCustomKey("claude_api_key", it)
                    },
                    label = { Text("Claude API Key (Backup Provider)", color = TextCyanMuted, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = HudBorderCyan,
                        focusedTextColor = TextCyanLight,
                        unfocusedTextColor = TextCyanLight
                    )
                )
            }
        }

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

        // Gemini Diagnostic Service Panel
        GeminiDiagnosticCard(viewModel = viewModel)

        LocalModelCard(viewModel = viewModel)

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
