plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.airsofttrackerapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.airsofttrackerapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = "<-- Paste your API KEY here -->"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.play.services.maps)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}