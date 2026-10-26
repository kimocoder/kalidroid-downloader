plugins {
    alias(libs.plugins.android.application)
}

// Monotonic build number used as versionCode, so self-update can compare builds.
// Falls back to 1 when building outside a git checkout (e.g. a source zip).
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

// Release signing comes from the environment (CI secrets). Without it,
// assembleRelease produces an unsigned APK.
val releaseKeystoreFile = providers.environmentVariable("KALIDROID_KEYSTORE_FILE").orNull

android {
    namespace = "com.nethunter.kalidroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nethunter.kalidroid"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = providers.environmentVariable("KALIDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KALIDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KALIDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
