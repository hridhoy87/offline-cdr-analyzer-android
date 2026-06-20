plugins {
    alias(libs.plugins.android.application)
    // Applies the native Chaquopy framework to execute Python logic directly in Java/Kotlin
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.example.offlinecdranalyzer"

    // Smoothly targets Android SDK 35
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.offlinecdranalyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // CHAQUOPY CONFIGURATION: Mandatory Native ABI targeting
        // Restricted to arm64-v8a to significantly reduce APK size for mobile devices
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // CHAQUOPY CONFIGURATION: Python Environment & Script Pip Requirements
    chaquopy {
        defaultConfig {
            version = "3.12"
            buildPython("python")
            pip {
                install("numpy")
                install("pandas")
                install("openpyxl")
                install("pdfplumber")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.13599879 rc2"
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}