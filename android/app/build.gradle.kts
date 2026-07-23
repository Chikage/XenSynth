plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "icu.ringona.xensynth"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "icu.ringona.xensynth"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 29
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++14")
                arguments("-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_shared")
            }
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
        configureEach {
            signingConfigs.findByName("shared")?.let {
                signingConfig = it
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation("junit:junit:4.13.2")
}
