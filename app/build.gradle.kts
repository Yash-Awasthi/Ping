import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

android {
    namespace = "com.ping.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ping.app"
        minSdk = 26           // BLE + Nearby Connections baseline
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Nearby Connections (offline BLE + Wi-Fi Direct transport)
    implementation(libs.play.services.nearby)

    // Gson — contact card JSON
    implementation(libs.gson)

    // Timber
    implementation(libs.timber)

    // CameraX — front-camera preview + frame analysis for hand gesture detection
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks Vision — HandLandmarker (21 landmarks used to build the gesture fingerprint)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // BouncyCastle — X25519 ECDH + AES-256-GCM (reliable across minSdk 26+)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
            val conn = URL(handModelUrl).openConnection() as HttpURLConnection
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

afterEvaluate {
    tasks.matching { task ->
        val n = task.name
        (n.startsWith("merge") && n.endsWith("Assets")) ||
            n.startsWith("lint") ||
            (n.startsWith("generate") && n.contains("Lint"))
    }.configureEach { dependsOn("downloadHandModel") }
}
