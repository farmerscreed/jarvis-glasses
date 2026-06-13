plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.echo.device.audio"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 29
        // sherpa-onnx native libs are arm64-v8a only in our slim AAR (covers the Pixel 8 + all
        // modern phones). On other ABIs on-device STT is unavailable and we fall back to cloud STT.
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    implementation(libs.vosk.android)
    // On-device STT (offline, no quota). Slim AAR = arm64-v8a only (sherpa-onnx 1.13.2).
    api(files("libs/sherpa-onnx-1.13.2-arm64.aar"))
}
