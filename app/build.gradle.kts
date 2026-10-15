import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Private signing material stays outside the repository and the APK.
val localSigningFile = File(System.getProperty("user.home"), ".neotransfer/signing.properties")
val localSigning = Properties().apply { if (localSigningFile.isFile) localSigningFile.inputStream().use(::load) }

android {
    namespace = "dev.duardo.neotransfer.plus"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.duardo.neotransfer.plus"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    signingConfigs {
        if (localSigningFile.isFile) create("localRelease") {
            storeFile = file(requireNotNull(localSigning.getProperty("storeFile")))
            storePassword = requireNotNull(localSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(localSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(localSigning.getProperty("keyPassword"))
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("localRelease")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":scanner"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
