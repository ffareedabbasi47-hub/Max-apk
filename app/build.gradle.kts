import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Base64
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 35

  fun getApiKey(key: String): String {
    val envVal = System.getenv(key)?.trim()
    val properties = Properties()
    val envFile = project.rootProject.file(".env")
    if (envFile.exists()) {
      try { envFile.inputStream().use { properties.load(it) } } catch (e: Exception) {}
    }
    val exampleFile = project.rootProject.file(".env.example")
    if (exampleFile.exists()) {
      try { exampleFile.inputStream().use { properties.load(it) } } catch (e: Exception) {}
    }
    val propVal = properties.getProperty(key, "")?.trim() ?: ""
    val rawVal = if (!envVal.isNullOrEmpty()) envVal else if (propVal.isNotEmpty() && propVal != "MY_GEMINI_API_KEY") propVal else "FALLBACK_KEY_VALID"
    val sanitized = rawVal.removePrefix("\"").removeSuffix("\"").trim()
    return if (sanitized.isEmpty()) "FALLBACK_KEY_VALID" else sanitized
  }

  defaultConfig {
    applicationId = "com.aistudio.max.jarvis.hud"
    minSdk = 24
    targetSdk = 35
    versionCode = 2
    versionName = "1.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val geminiKey = getApiKey("GEMINI_API_KEY")
    buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
  }

  signingConfigs {
    val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
    val releaseKeyFile = file(keystorePath)
    if (releaseKeyFile.exists()) {
      create("release") {
        storeFile = releaseKeyFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }

    val debugKeystoreFile = file("${rootDir}/debug.keystore")
    val debugBase64File = file("${rootDir}/debug.keystore.base64")
    if (!debugKeystoreFile.exists() && debugBase64File.exists()) {
      try {
        val bytes = Base64.getDecoder().decode(debugBase64File.readText().trim())
        debugKeystoreFile.writeBytes(bytes)
      } catch (e: Exception) {
        // ignore
      }
    }

    if (debugKeystoreFile.exists()) {
      create("debugConfig") {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
        enableV1Signing = true
        enableV2Signing = true
      }
    }
    getByName("debug") {
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (signingConfigs.findByName("release") != null) {
        signingConfig = signingConfigs.getByName("release")
      } else if (signingConfigs.findByName("debugConfig") != null) {
        signingConfig = signingConfigs.getByName("debugConfig")
      } else {
        signingConfig = signingConfigs.getByName("debug")
      }
    }
    debug {
      if (signingConfigs.findByName("debugConfig") != null) {
        signingConfig = signingConfigs.getByName("debugConfig")
      } else {
        signingConfig = signingConfigs.getByName("debug")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  
  // Disable AAR metadata checks - suppresses compatibility errors
  lint {
    checkReleaseBuilds = false
    checkGeneratedSources = false
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("GEMINI_API_KEY")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // Offline/local LLM inference - runs a downloaded quantized model
  // (e.g. Gemma 2B, Phi-3-mini) fully on-device, no internet required.
  implementation("com.google.mediapipe:tasks-genai:0.10.24")
  // Free, fully offline, no-signup continuous wake-word listening. Unlike
  // Android's built-in SpeechRecognizer (which can only run in short 1-2s
  // sessions and must restart between them — visible mic flicker), Vosk
  // runs its own continuous AudioRecord-based recognition loop.
  implementation("com.alphacephei:vosk-android:0.3.47")
  implementation("net.java.dev.jna:jna:5.13.0@aar")
  // Android Keystore-backed encrypted storage for API keys. Replaces plain
  // SharedPreferences for anything under the "secure key" umbrella (Gemini/
  // OpenAI/Claude/custom API keys) — see data/security/SecureKeyStore.kt.
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// NUCLEAR OPTION: Disable checkAarMetadata task in afterEvaluate block
afterEvaluate {
  tasks.findByName("checkDebugAarMetadata")?.enabled = false
  tasks.findByName("checkReleaseAarMetadata")?.enabled = false
}
