import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val geminiLocalProperties = Properties()
rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
    geminiLocalProperties.load(it)
}
val geminiApiKey = geminiLocalProperties.getProperty("geminiApiKey", "").trim()
val geminiApiKeyLiteral = "\"${geminiApiKey.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")}\""

android {
    namespace = "com.example.drishtijava"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.drishtijava"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "GEMINI_API_KEY", geminiApiKeyLiteral)
        }
        release {
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.lifecycle.service)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
    implementation(libs.mlkit.face.detection)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
