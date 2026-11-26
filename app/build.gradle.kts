import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.audify.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.audify.app"
        minSdk = 24
        targetSdk = 35
        // Version code from the build time (in minutes), so every new APK installs as an update
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        versionCode = (System.currentTimeMillis() / 60_000L - 29_000_000L).toInt()
        versionName = "5.3 ($stamp)"
    }

    // yt-dlp + Python + ffmpeg are native per-CPU, so build one APK per ABI to keep size down.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Same signing key for every build (kept in CI's private cache), so new APKs replace old ones
    val keystorePath = System.getenv("AUDIFY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("audify") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AUDIFY_KEY_PASS")
                keyAlias = "audify"
                keyPassword = System.getenv("AUDIFY_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("audify") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // needed by NewPipeExtractor on Android < 13
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Required by youtubedl-android: native binaries must be extracted to disk
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdl")

    // Second extractor alongside yt-dlp: pure Java, no Python, much faster to resolve streams
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-datasource:1.5.0")
    implementation("androidx.media3:media3-database:1.5.0")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
