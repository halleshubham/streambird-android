import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Release signing: environment variables win, then an untracked keystore.properties at the repo root.
// With neither, the release build is produced UNSIGNED-by-us (debug-signed APK / unsigned AAB) so forks and CI still build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    (System.getenv(name) ?: keystoreProps.getProperty(name))?.takeIf { it.isNotBlank() }
val releaseKeystoreFile = signingValue("STREAMBIRD_KEYSTORE_FILE")?.let { rootProject.file(it).takeIf { f -> f.exists() } ?: file(it) }
val hasReleaseSigning = releaseKeystoreFile?.exists() == true &&
    signingValue("STREAMBIRD_KEYSTORE_PASSWORD") != null &&
    signingValue("STREAMBIRD_KEY_ALIAS") != null &&
    signingValue("STREAMBIRD_KEY_PASSWORD") != null

// Single source of truth for the version: gradle.properties (override with -PstreambirdVersionCode=... -PstreambirdVersionName=...).
val appVersionCode = (project.findProperty("streambirdVersionCode") as String?)?.toInt() ?: 108
val appVersionName = (project.findProperty("streambirdVersionName") as String?) ?: "1.0.8"

android {
    namespace = "app.streambird.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shackyapps.streambird"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
        // Server the app talks to. Override at build time: ./gradlew assembleDebug -PstreambirdBaseUrl=https://staging.example.com
        val baseUrl = (project.findProperty("streambirdBaseUrl") as String?) ?: "https://streambird.app"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingValue("STREAMBIRD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("STREAMBIRD_KEY_ALIAS")
                keyPassword = signingValue("STREAMBIRD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 1.0.0 ships UNSHRUNK on purpose (no device to test R2/JNI keep rules); see docs/PLAY-STORE.md "Minify decision".
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore credentials the release APK falls back to the debug key (never usable for Play); the AAB stays unsigned.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    bundle {
        // Play delivers only the matching ABI / screen density / language to each device.
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // HTTP: OkHttp + kotlinx.serialization (a thin ApiClient instead of Retrofit, see docs/SPIKE.md).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")


    // Chrome Custom Tab for "Continue with Google" (falls back to ACTION_VIEW without a Custom Tabs provider).
    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Prebuilt libwebrtc (package org.webrtc) -- see docs/SPIKE.md for why this one.
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
