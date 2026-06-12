plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.echo.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.echo.companion"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // dev = local Supabase over adb reverse (cleartext, hardcoded test login allowed);
    // prod = cloud jarvis-prod project (TLS only). Anon keys are public by design (RLS enforces).
    flavorDimensions += "backend"
    productFlavors {
        create("dev") {
            dimension = "backend"
            buildConfigField("String", "SUPABASE_URL", "\"http://127.0.0.1:54421\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH\"")
            buildConfigField("boolean", "DEV_LOGIN", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "SUPABASE_URL", "\"https://agtuimnppqbrjocuzqsk.supabase.co\"")
            buildConfigField(
                "String",
                "SUPABASE_ANON_KEY",
                "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFndHVpbW5wcHFicmpvY3V6cXNrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEyNTU2ODQsImV4cCI6MjA5NjgzMTY4NH0.KSExgwXG79KcCJ4FbBpWTkddewO_SKjYYVMSRHRXEyE\"",
            )
            buildConfigField("boolean", "DEV_LOGIN", "false")
            // Cleartext must stay ON even in prod: the glasses media sync is plain HTTP over
            // Wi-Fi Direct (http://192.168.49.x/files/..., firmware constraint — no TLS on the
            // device). All cloud traffic is https:// regardless; this flag never downgrades it.
            // Phase F: revisit with a network-security-config if a subnet-scoped rule becomes possible.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources { noCompress += "tflite" } // model must be mmap-able at runtime

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Project modules
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(project(":memory"))
    implementation(project(":assistant"))
    implementation(project(":device:ble"))
    implementation(project(":device:audio"))
    implementation(project(":device:wifi"))

    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media)
    implementation(libs.work.runtime)
    implementation(libs.mediapipe.tasks.text)

    debugImplementation(libs.androidx.ui.tooling)
}
