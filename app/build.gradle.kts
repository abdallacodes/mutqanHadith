plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mutqan.hadith"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mutqan.hadith"
        minSdk = 30
        targetSdk = 37
        versionCode = 10401
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: the key is used only when KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD
    // are set in the environment and a keystore is found, first of: $KEYSTORE_FILE,
    // app/keystore/release.keystore, ~/.android/mutqan-hadith-release.keystore. Nothing secret
    // lives in the repository. Otherwise the release build is signed with the debug key, so
    // `assembleRelease` still yields an installable APK locally.
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
    val releaseKeystore = listOfNotNull(
        env("KEYSTORE_FILE")?.let { file(it) },
        file("keystore/release.keystore"),
        file(System.getProperty("user.home") + "/.android/mutqan-hadith-release.keystore"),
    ).firstOrNull { it.exists() }.takeIf {
        env("KEYSTORE_PASSWORD") != null && env("KEY_ALIAS") != null && env("KEY_PASSWORD") != null
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("KEYSTORE_PASSWORD")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}