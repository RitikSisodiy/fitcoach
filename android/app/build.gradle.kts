import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: MAJOR.MINOR from version.properties, PATCH = CI build number (0 for local builds).
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val vMajor = versionProps.getProperty("versionMajor").toInt()
val vMinor = versionProps.getProperty("versionMinor").toInt()
val vPatch = (System.getenv("BUILD_NUMBER") ?: "0").toInt()

android {
    namespace = "com.fitcoach.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fitcoach.app"
        minSdk = 28
        targetSdk = 35
        versionCode = vMajor * 1_000_000 + vMinor * 10_000 + vPatch
        versionName = "$vMajor.$vMinor.$vPatch"
    }

    // CI signs releases with a fixed key from repository secrets, so installed apps can update in place.
    signingConfigs {
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release key on CI; local release builds fall back to the debug key.
            signingConfig = signingConfigs.getByName(if (System.getenv("SIGNING_KEYSTORE") != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Gemini Live WebSocket for voice calls

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
