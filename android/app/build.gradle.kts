import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jarvis.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 34          // Android 14: newer rules for services, alarms and launches can be relied on
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        release {
            // R8 is left off until the keep rules have been tested on a device.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// On-device AI (LiteRT-LM) is opt-in: it needs a newer Kotlin than the one pinned in libs.versions.toml and has never been compiled
// here. With `-PenableLiteRt=true` (or enableLiteRt=true in gradle.properties) the real engine in src/litert replaces the empty one in
// src/noLocal. See docs/ai/LOCAL_MODELS.md for the steps.
val enableLiteRt = providers.gradleProperty("enableLiteRt").orNull == "true"
android.sourceSets.getByName("main").java.srcDir(if (enableLiteRt) "src/litert/java" else "src/noLocal/java")

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Hands-free: on-device detector for the word "Jarvis" (needs a free Picovoice AccessKey that the person enters in Prefs).
    implementation(libs.porcupine.android)
    // Offline wake word with no account or key (the default). Vosk calls its native code through JNA, which ships as an Android library.
    implementation(libs.vosk.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    // Reading the offline library: its content blocks are zstd (current Kiwix files) or xz (older ones). zstd ships as an Android library with its native code.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstdJni.get()}@aar")
    implementation(libs.xz)

    if (enableLiteRt) implementation(libs.litertlm)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
