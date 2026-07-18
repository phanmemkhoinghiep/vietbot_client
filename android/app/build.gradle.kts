plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "vn.vietbot.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.vietbot.client"
        minSdk = 24
        targetSdk = 35

        // Auto-increment versionCode on each build.
        // Pass -PbuildNumber=N or env BUILD_NUMBER=N to bump.
        // Default: start at 12 (previous build was 11).
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull()
            ?: (project.findProperty("buildNumber") as String?)?.toIntOrNull()
            ?: 1
        versionCode = 11 + buildNumber
        versionName = "1.${buildNumber}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build native libraries for all common Android ABIs (32-bit + 64-bit).
        // Google Play requires 64-bit for apps with native libs since Aug 2019.
        // To build a subset, pass:
        //   ./gradlew :app:assembleDebug -PdefaultAbiFilters=armeabi-v7a,arm64-v8a
        // or edit the list below.
        val abiList = (project.findProperty("defaultAbiFilters") as String?)
            ?: "armeabi-v7a,arm64-v8a,x86,x86_64"
        ndk {
            abiFilters += abiList.split(",").map { it.trim() }
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags  += "-std=c++17"
            }
        }
    }

    signingConfigs {
        create("release") {
            // Use debug keystore for local builds; override with real keystore for production.
            storeFile = file(System.getenv("ANDROID_KEYSTORE")
                ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.okhttp)
    implementation(libs.opus.v131)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)

    implementation(libs.paho.mqtt.android)
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")

    // HeyCyan Glasses SDK
    implementation(files("libs/glasses_sdk_20250723_v01.aar"))
    implementation("com.github.getActivity:XXPermissions:20.0")
    implementation("org.greenrobot:eventbus:3.2.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.json)
}
kapt {
    correctErrorTypes = true
}