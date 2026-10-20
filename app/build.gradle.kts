import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.maiconerick.xgamelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maiconerick.xgamelauncher"
        minSdk = 26
        targetSdk = 35
        // Every build gets a newer version: minutes since 2025-01-01 (fits an Int for decades)
        // and a readable timestamp name. The in-app updater compares versionCode.
        val buildMinutes = (System.currentTimeMillis() / 60_000L - 29_000_000L).toInt()
        versionCode = buildMinutes
        versionName = "0.2." + SimpleDateFormat("yyMMdd.HHmm").apply {
            timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
        }.format(Date())
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Non-debuggable build for the handhelds: ART fully compiles it and Compose
            // drops its debug overhead — much snappier on the RG353M's RK3566. Signed with
            // the debug key so it installs over (and is replaced by) debug builds freely.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/*.md"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-graphics")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil for images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // HTTP + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ROM downloader: single files out of the romgi catalog's MiNERVA torrents.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
