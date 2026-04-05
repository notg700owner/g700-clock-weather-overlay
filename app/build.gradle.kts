import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val updateOwner = providers.gradleProperty("updateOwner").orElse("notg700owner")
val updateRepo = providers.gradleProperty("updateRepo").orElse("g700-clock-weather-overlay")
val updateBranch = providers.gradleProperty("updateBranch").orElse("main")
val updateMetadataPath = providers.gradleProperty("updateMetadataPath").orElse("update/update.json")

android {
    namespace = "com.g700.clockweather"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.g700.clockweather"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "2.1.5"

        buildConfigField("String", "UPDATE_OWNER", "\"${updateOwner.get()}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${updateRepo.get()}\"")
        buildConfigField("String", "UPDATE_BRANCH", "\"${updateBranch.get()}\"")
        buildConfigField("String", "UPDATE_METADATA_PATH", "\"${updateMetadataPath.get()}\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
