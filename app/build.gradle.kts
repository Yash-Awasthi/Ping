import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ping.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.ping.app"
        minSdk = 26           // BLE + Nearby Connections baseline
        targetSdk = 37
        versionCode = 7
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing — reads keystore.properties (gitignored) if present.
    val keystorePropsFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                val props = Properties().apply { load(FileInputStream(keystorePropsFile)) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
        release {
            // MediaPipe is kept whole in proguard-rules.pro; R8 once stripped its JNI-reached classes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Per-ABI APKs — MediaPipe native libs dominate size; a universal APK is huge.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        optIn.add("kotlin.RequiresOptIn")
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Nearby Connections (offline BLE + Wi-Fi Direct transport)
    implementation(libs.play.services.nearby)

    // Contact card and file-room wire JSON
    implementation(libs.kotlinx.serialization.json)

    // Timber
    implementation(libs.timber)

    // CameraX — front-camera preview + frame analysis for hand gesture detection
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)

    // MediaPipe Tasks Vision — HandLandmarker (21 landmarks used to build the gesture fingerprint)
    implementation(libs.mediapipe.tasks.vision)

    // Unit tests — pure JVM logic only (gesture fingerprint, crypto primitives).
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests: Room DAOs against an in-memory database (needs a device or emulator).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room3.testing)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.runtime.compose)
}

// ---------------------------------------------------------------------------
// Download the MediaPipe hand-landmarker model into assets/ before build.
// Skips when already present. For offline builds, place the file manually at
//   app/src/main/assets/hand_landmarker.task
// ---------------------------------------------------------------------------
tasks.register("downloadHandModel") {
    description = "Download hand_landmarker.task from the MediaPipe model hub"
    group = "ping"
    val modelFile = file("src/main/assets/hand_landmarker.task")
    val handModelUrl = "https://storage.googleapis.com/mediapipe-models/" +
        "hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    outputs.file(modelFile)
    doLast {
        if (modelFile.exists() && modelFile.length() > 0) {
            println("hand_landmarker.task already present — skipping download.")
            return@doLast
        }
        modelFile.parentFile.mkdirs()
        try {
            println("Downloading hand_landmarker.task from $handModelUrl …")
            val conn = URI(handModelUrl).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.connect()
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { inp -> modelFile.outputStream().use { out -> inp.copyTo(out) } }
            println("Downloaded ${modelFile.length()} bytes to ${modelFile.absolutePath}")
        } catch (e: Exception) {
            modelFile.takeIf { it.exists() }?.delete()
            throw IOException(
                "Failed to download hand_landmarker.task. For offline builds place it at:\n" +
                    "  ${modelFile.absolutePath}", e
            )
        }
    }
}

tasks.configureEach {
    val n = name
    if ((n.startsWith("merge") && n.endsWith("Assets")) || n.startsWith("lint") ||
        (n.startsWith("generate") && n.contains("Lint"))
    ) dependsOn("downloadHandModel")
}
