plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.traccarwatch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.traccarwatch"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing is optional: it only applies when the key properties are provided
    // (e.g. in ~/.gradle/gradle.properties), so anyone can still build the debug APK.
    val storeFile = providers.gradleProperty("TRACCAR_STORE_FILE").orNull
    if (storeFile != null) {
        signingConfigs.create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("TRACCAR_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("TRACCAR_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("TRACCAR_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.traccar.client.sdk)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    testImplementation(libs.junit)
}
