plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.calorie.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.calorie.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    // Language list for the per-app language setting on Android 13+.
    androidResources {
        generateLocaleConfig = true
    }
    // The Anthropic SDK pulls in Jackson and Kotlin: the same metadata files in several jars.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    // Retry queue for Claude requests: resends in the background once the service is back.
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    ksp("androidx.room:room-compiler:2.7.2")
    // Claude API (food recognition from photos and text).
    implementation("com.anthropic:anthropic-java:2.66.0")
    // Claude Console pages in an in-app browser tab.
    implementation("androidx.browser:browser:1.8.0")
    // Google barcode scanner: ready-made UI, no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Google services pull in an old Fragment, and activity results require 1.3+.
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
