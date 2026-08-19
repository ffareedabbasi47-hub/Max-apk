package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.api.diagnostics.ProviderDiagnosticResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MaxViewModel
import kotlinx.coroutines.delay

// SETTINGS REDESIGN: replaces the old flat "MULTI-PROVIDER API KEY MANAGEMENT"
// box (six unmasked text fields dumped on one screen with no per-provider
// status or test) with a clear per-provider card: masked key, show/hide,
// silent Save (no speaking on every keystroke), a real [TEST CONNECTION]
// that reports the actual HTTP result, and a connected/not-configured badge.
@Composable
fun AiProvidersSection(viewModel: MaxViewModel) {
    var showAdvanced by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "AI PROVIDERS",
            color = CyanPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "MAX tries these in order — Gemini first, then OpenAI, then Claude — and falls back to the offline model if all three fail.",
            color = TextCyanMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        // Gemini — reads whichever of Slot 1/2/3/Custom is filled first, same
        // priority order MultiBrainManager itself uses for real requests.
        val geminiResult by viewModel.geminiDiagnosticResult.collectAsStateWithLifecycle()
        var geminiKey by remember { mutableStateOf(viewModel.getApiKeySlot(1)) }
        ProviderCard(
            providerLabel = "Google Gemini",
            modelLabel = com.example.data.api.GeminiModels.DIAGNOSTIC_DEFAULT_MODEL,
            keyValue = geminiKey,
            onKeyChange = { geminiKey = it },
            onSave = { viewModel.saveApiKeySlot(1, geminiKey) },
            onDelete = { viewModel.deleteApiKey("api_key_slot_1"); geminiKey = "" },
            onTest = {
                val activeKey = listOf(
                    geminiKey,
                    viewModel.getApiKeySlot(2),
                    viewModel.getApiKeySlot(3),
                    viewModel.getCustomKey("custom_gemini_api_key")
                ).firstOrNull { it.isNotBlank() } ?: ""
                viewModel.runGeminiDiagnosticCheck(activeKey)
            },
            isSuccess = geminiResult?.isSuccess,
            statusText = geminiResult?.let {
                if (it.isSuccess) "Connected — ${it.latencyMs}ms" else "${it.statusCategory}: ${it.errorMessage ?: "failed"}"
            }
        )

        // OpenAI
        val openAiResult by viewModel.openAiDiagnosticResult.collectAsStateWithLifecycle()
        var openAiKey by remember { mutableStateOf(viewModel.getCustomKey("openai_api_key")) }
        ProviderCard(
            providerLabel = "OpenAI",
            modelLabel = com.example.data.api.diagnostics.ProviderDiagnosticService.OPENAI_MODEL,
            keyValue = openAiKey,
            onKeyChange = { openAiKey = it },
            onSave = { viewModel.saveCustomKey("openai_api_key", openAiKey) },
            onDelete = { viewModel.deleteApiKey("openai_api_key"); openAiKey = "" },
            onTest = { viewModel.runOpenAiDiagnosticCheck(openAiKey) },
            isSuccess = openAiResult?.isSuccess,
            statusText = openAiResult?.let {
                if (it.isSuccess) "Connected — ${it.latencyMs}ms" else "${it.statusCategory}: ${it.errorMessage ?: "failed"}"
            }
        )

        // Claude
        val claudeResult by viewModel.claudeDiagnosticResult.collectAsStateWithLifecycle()
        var claudeKey by remember { mutableStateOf(viewModel.getCustomKey("claude_api_key")) }
        ProviderCard(
            providerLabel = "Anthropic Claude",
            modelLabel = com.example.data.api.diagnostics.ProviderDiagnosticService.CLAUDE_MODEL,
            keyValue = claudeKey,
            onKeyChange = { claudeKey = it },
            onSave = { viewModel.saveCustomKey("claude_api_key", claudeKey) },
            onDelete = { viewModel.deleteApiKey("claude_api_key"); claudeKey = "" },
            onTest = { viewModel.runClaudeDiagnosticCheck(claudeKey) },
            isSuccess = claudeResult?.isSuccess,
            statusText = claudeResult?.let {
                if (it.isSuccess) "Connected — ${it.latencyMs}ms" else "${it.statusCategory}: ${it.errorMessage ?: "failed"}"
            }
        )

        Button(
            onClick = {
                val activeGeminiKey = listOf(
                    geminiKey, viewModel.getApiKeySlot(2), viewModel.getApiKeySlot(3),
                    viewModel.getCustomKey("custom_gemini_api_key")
                ).firstOrNull { it.isNotBlank() } ?: ""
                viewModel.runGeminiDiagnosticCheck(activeGeminiKey)
                viewModel.runOpenAiDiagnosticCheck(openAiKey)
                viewModel.runClaudeDiagnosticCheck(claudeKey)
            },
            colors = ButtonDefaults.buttonColors(containerColor = HudSurfaceVariant, contentColor = CyanPrimary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("TEST ALL PROVIDERS", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        // Advanced — the extra Gemini key slots (2/3) and the standalone custom
        // Gemini key field power the multi-key rotation MultiBrainManager already
        // does on quota errors; kept out of the main view since a normal user
        // only ever needs to fill in Slot 1.
        Text(
            text = if (showAdvanced) "▾ ADVANCED" else "▸ ADVANCED",
            color = CyanSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { showAdvanced = !showAdvanced }
        )
        if (showAdvanced) {
            var keySlot2 by remember { mutableStateOf(viewModel.getApiKeySlot(2)) }
            var keySlot3 by remember { mutableStateOf(viewModel.getApiKeySlot(3)) }
            var customGeminiKey by remember { mutableStateOf(viewModel.getCustomKey("custom_gemini_api_key")) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Extra Gemini keys — MAX rotates to the next one automatically if the primary key hits a quota limit.",
                    color = TextCyanMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                MaskedKeyField(keySlot2, { keySlot2 = it; viewModel.saveApiKeySlot(2, it) }, "Gemini Slot 2 (Backup)")
                MaskedKeyField(keySlot3, { keySlot3 = it; viewModel.saveApiKeySlot(3, it) }, "Gemini Slot 3 (Backup)")
                MaskedKeyField(customGeminiKey, { customGeminiKey = it; viewModel.saveCustomKey("custom_gemini_api_key", it) }, "Custom Gemini Key")
            }
        }
    }
}

@Composable
private fun ProviderCard(
    providerLabel: String,
    modelLabel: String,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    isSuccess: Boolean?,
    statusText: String?
) {
    val configured = keyValue.isNotBlank()
    val badgeColor = when {
        isSuccess == true -> NeonGreenStatus
        isSuccess == false -> NeonAmberAlert
        configured -> CyanPrimary
        else -> TextCyanMuted
    }
    var justSaved by remember { mutableStateOf(false) }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(2000)
            justSaved = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface, shape = RoundedCornerShape(12.dp))
            .border(1.dp, badgeColor.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(providerLabel, color = TextCyanLight, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(badgeColor, shape = RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (configured) "Configured" else "Not configured",
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text("Model: $modelLabel", color = TextCyanMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

            MaskedKeyField(keyValue, onKeyChange, "API Key")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(); justSaved = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanSecondary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (justSaved) "✓ SAVED" else "SAVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = onTest,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("TEST CONNECTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            if (configured) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("DELETE API KEY", color = NeonAmberAlert, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }

            statusText?.let {
                Text(
                    text = (if (isSuccess == true) "✓ " else "✕ ") + it,
                    color = badgeColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MaskedKeyField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = TextCyanMuted, fontSize = 10.sp) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                    tint = CyanPrimary
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanPrimary,
            unfocusedBorderColor = HudBorderCyan,
            focusedTextColor = TextCyanLight,
            unfocusedTextColor = TextCyanLight
        )
    )
}
