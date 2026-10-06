plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

import java.util.Properties

// Stable output dir (avoids locked default app/build R.jar on some Windows setups).
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build-app-timing"))

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl = (localProps.getProperty("SUPABASE_URL") ?: "").trim()
val supabaseAnonKey = (localProps.getProperty("SUPABASE_ANON_KEY") ?: "").trim()
// Own upload server (/server). Defaults to the live server; local.properties or
// environment variables (GitHub Actions secrets) override. Takes priority over Supabase.
fun serverSetting(name: String, default: String): String =
    (localProps.getProperty(name) ?: System.getenv(name))?.trim()?.takeIf { it.isNotEmpty() } ?: default
val tagServerUrl = serverSetting("TAG_SERVER_URL", "https://collar.justkodez.com")
// Only lets an install register; each install then gets its own token (see server/README.md).
val tagEnrollKey = serverSetting("TAG_ENROLL_KEY", "44AwhowPKbbWQ4rwac2CabD6pAjgMtm0k96l1dzKn3RPjd")

android {
    namespace = "com.nordic.tagmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nordic.tagmobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "0.9.1"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "TAG_SERVER_URL", "\"$tagServerUrl\"")
        buildConfigField("String", "TAG_ENROLL_KEY", "\"$tagEnrollKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Nordic BLE stack (Maven â€” no Toolbox clone)
    implementation("no.nordicsemi.android:ble-ktx:2.9.0")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")

    // Cloud foundation: local index + background upload
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Supabase via REST (OkHttp) + DoH to bypass poisoned Wiâ€‘Fi DNS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}
