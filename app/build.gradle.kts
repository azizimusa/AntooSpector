import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Dashboard credentials live in local.properties, which is not version-controlled.
// Absent, the app simply captures locally and ships nothing.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun setting(name: String): String =
    (local.getProperty(name) ?: System.getenv(name.replace('.', '_').uppercase())).orEmpty()

android {
    namespace = "gg.padu.ke"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "gg.padu.ke"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ANTOO_ENDPOINT", "\"${setting("antoo.endpoint")}\"")
        buildConfigField("String", "ANTOO_KEY", "\"${setting("antoo.key")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(project(":httpmonitor"))
    implementation(libs.material)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}