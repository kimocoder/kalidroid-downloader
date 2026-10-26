plugins {
    alias(libs.plugins.android.application)
}

val gitCommitSha = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }.getOrElse("")

android {
    namespace = "com.nethunter.kalidroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nethunter.kalidroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        resValue("string", "git_commit_sha", gitCommitSha)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        resValues = true
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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}