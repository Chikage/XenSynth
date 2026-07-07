plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "icu.ringona.xensynth"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "icu.ringona.xensynth"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.3.7"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++14")
                arguments("-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        if (project.hasProperty("sign.store.file") &&
            project.hasProperty("sign.store.password") &&
            project.hasProperty("sign.key.alias") &&
            project.hasProperty("sign.key.password")
        ) {
            val configuredStoreFile = file(project.property("sign.store.file") as String)
            if (configuredStoreFile.isFile) {
                create("shared") {
                    storeFile = configuredStoreFile
                    storePassword = project.property("sign.store.password") as String
                    keyAlias = project.property("sign.key.alias") as String
                    keyPassword = project.property("sign.key.password") as String
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let {
                signingConfig = it
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("shared")?.let {
                signingConfig = it
            }
        }
    }

    externalNativeBuild {
        cmake {
            path(file("src/main/cpp/CMakeLists.txt"))
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
