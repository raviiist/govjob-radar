import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val cfg = Properties().apply {
    val f = rootProject.file("app-config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfgStr(key: String): String = "\"" + (cfg.getProperty(key) ?: "").trim().replace("\"", "") + "\""

android {
    namespace = "com.dhruvaxtechnologies.govjobradar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dhruvaxtechnologies.govjobradar"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        buildConfigField("String", "FEED_BASE_URL", cfgStr("FEED_BASE_URL"))
        buildConfigField("String", "ADMIN_EMAIL", cfgStr("ADMIN_EMAIL"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", cfgStr("FIREBASE_PROJECT_ID"))
        buildConfigField("String", "FIREBASE_APP_ID", cfgStr("FIREBASE_APP_ID"))
        buildConfigField("String", "FIREBASE_API_KEY", cfgStr("FIREBASE_API_KEY"))
        buildConfigField("String", "FIREBASE_SENDER_ID", cfgStr("FIREBASE_SENDER_ID"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", cfgStr("GOOGLE_WEB_CLIENT_ID"))
    }

    signingConfigs {
        create("release") {
            val sf = project.findProperty("gjr.storeFile") as String?
            if (sf != null) {
                storeFile = file(sf)
                storePassword = project.findProperty("gjr.storePassword") as String?
                keyAlias = project.findProperty("gjr.keyAlias") as String?
                keyPassword = project.findProperty("gjr.keyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.findProperty("gjr.storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key as release when available, so Google sign-in (Admin) also works on debug builds.
            if (project.findProperty("gjr.storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
