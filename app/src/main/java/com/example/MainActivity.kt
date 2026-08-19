package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.system.MaxWakeService
import com.example.ui.components.HudBottomNav
import com.example.ui.components.HudHeader
import com.example.ui.components.HudNavDestination
import com.example.ui.screens.*
import com.example.ui.theme.HudBackground
import com.example.ui.theme.MAXTheme
import com.example.ui.viewmodel.MaxViewModel

class MainActivity : ComponentActivity() {

    private var maxViewModelInstance: MaxViewModel? = null

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MaxWakeService.ACTION_WAKE_WORD_DETECTED) {
                maxViewModelInstance?.testWakeWord()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            IntentFilter(MaxWakeService.ACTION_WAKE_WORD_DETECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // BUGFIX: this used to call handleIntent() here, before setContent{} ran —
        // but maxViewModelInstance is only assigned once the composable below
        // executes viewModel(), so on a cold start (Activity process was killed
        // while another app like WhatsApp was in the foreground, which Android
        // does routinely under memory pressure) this call hit a null ViewModel
        // and silently did nothing. That's why "Max" would open one app fine,
        // then stop responding entirely afterward: the wake service had already
        // paused itself for the conversation and nothing ever resumed it. The
        // wake intent is now handled inside setContent, once the ViewModel
        // actually exists, for both cold starts and the reused-Activity case.
        val pendingWakeIntent = intent

        setContent {
            MAXTheme {
                val maxViewModel: MaxViewModel = viewModel()
                maxViewModelInstance = maxViewModel

                LaunchedEffect(Unit) {
                    if (pendingWakeIntent?.getBooleanExtra("WAKE_WORD_TRIGGERED", false) == true) {
                        maxViewModel.testWakeWord()
                    }
                }

                val telemetry by maxViewModel.systemTelemetry.collectAsState()
                var currentDestination by remember { mutableStateOf(HudNavDestination.HOME) }

                // PHASE 10 — MediaProjection consent flow. This dialog is
                // Android's own system UI and can only be launched from an
                // Activity; the ViewModel just raises a request flag and this
                // effect answers it.
                val screenCaptureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                        val metrics = resources.displayMetrics
                        val serviceIntent = Intent(this@MainActivity, com.example.system.ScreenCaptureService::class.java).apply {
                            putExtra(com.example.system.ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(com.example.system.ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                            putExtra(com.example.system.ScreenCaptureService.EXTRA_WIDTH, metrics.widthPixels)
                            putExtra(com.example.system.ScreenCaptureService.EXTRA_HEIGHT, metrics.heightPixels)
                            putExtra(com.example.system.ScreenCaptureService.EXTRA_DENSITY, metrics.densityDpi)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                        maxViewModelInstance?.onScreenCaptureGranted()
                    } else {
                        maxViewModelInstance?.onScreenCaptureDenied()
                    }
                }

                val screenShareRequested by maxViewModel.screenShareConsentRequested.collectAsState()
                LaunchedEffect(screenShareRequested) {
                    if (screenShareRequested) {
                        val projectionManager = getSystemService(MediaProjectionManager::class.java)
                        val captureIntent = projectionManager?.createScreenCaptureIntent()
                        if (captureIntent != null) {
                            screenCaptureLauncher.launch(captureIntent)
                        } else {
                            maxViewModelInstance?.onScreenCaptureDenied()
                        }
                        maxViewModel.consumeScreenShareRequest()
                    }
                }

                // Check accessibility status when resumed
                val context = LocalContext.current
                DisposableEffect(Unit) {
                    maxViewModel.checkAccessibilityStatus(context)
                    onDispose {}
                }

                // Runtime Permissions Launcher
                val permissionsToRequest = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    // BUGFIX: the Settings screen showed the wake-word switch as ON
                    // by default, but nothing ever actually started MaxWakeService
                    // unless the user manually flipped the switch off and on again.
                    // Now that mic permission is resolved, start it for real.
                    if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
                        maxViewModelInstance?.toggleBackgroundWakeService(this@MainActivity, true)
                    }
                }

                LaunchedEffect(Unit) {
                    val missing = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // Mic permission was already granted from a previous launch —
                        // start wake listening immediately instead of waiting for the
                        // user to toggle the Settings switch manually.
                        maxViewModelInstance?.toggleBackgroundWakeService(this@MainActivity, true)
                    }
                }

                val immersiveHome = currentDestination == HudNavDestination.HOME

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(HudBackground),
                    topBar = {
                        if (!immersiveHome) HudHeader(telemetry = telemetry)
                    },
                    bottomBar = {
                        if (!immersiveHome) {
                            HudBottomNav(
                                currentDestination = currentDestination,
                                onNavigate = { currentDestination = it }
                            )
                        }
                    },
                    containerColor = HudBackground
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (immersiveHome) Modifier else Modifier.padding(innerPadding))
                            .background(HudBackground)
                    ) {
                        when (currentDestination) {
                            HudNavDestination.HOME -> HomeScreen(viewModel = maxViewModel)
                            HudNavDestination.CONTROL -> SystemControlScreen(viewModel = maxViewModel)
                            HudNavDestination.VISION -> ScreenAssistScreen(viewModel = maxViewModel)
                            HudNavDestination.TOOLS -> ToolsTabScreen(viewModel = maxViewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("WAKE_WORD_TRIGGERED", false)) {
            maxViewModelInstance?.testWakeWord()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "wakeReceiver already unregistered: ${e.message}")
        }
        super.onDestroy()
    }
}

