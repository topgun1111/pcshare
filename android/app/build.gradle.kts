import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One VERSION file for PC and Android (repo root). versionCode formula = core `versionCode()`.
val versionText: String = rootProject.projectDir.resolve("../VERSION")
    .takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "0.0.0" }
val versionParts: List<Int> = versionText.split(".")
    .map { s -> s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
// CI passes BUILD_NUMBER (= GitHub run number); locally 0. versionCode = (X*10000 + Y*100 + Z) * 1000 + build, so every CI build
// is newer than the one before and phones accept it as an update (same formula as core `updateVersionCode`).
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: "0").toIntOrNull()?.coerceIn(0, 999) ?: 0
val computedVersionCode: Int = (versionParts.getOrElse(0) { 0 } * 10000 +
    versionParts.getOrElse(1) { 0 } * 100 + versionParts.getOrElse(2) { 0 }) * 1000 + buildNumber

android {
    namespace = "com.takipli.oynat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.takipli.oynat"
        minSdk = 29
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = versionText
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        // GITHUB_REPOSITORY ("owner/name") exists on the CI runner; locally empty (type it in the Updates screen).
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
        ndk { abiFilters += "arm64-v8a" } // phone only (Snapdragon 8 Gen 3); no x86 emulator
    }

    // Two builds from one code base (CI matrix in .github/workflows/android.yml):
    //   pose = YOLOv8s/YOLO11m pose + optical-flow trackers (the current app)
    //   sam  = same, plus the SAM-type tracker models (EdgeTAM); installs next to the pose app (own applicationId)
    // Models live in src/<flavor>/assets/models (CI fills them; git-ignored). Code can branch on BuildConfig.ENGINE.
    flavorDimensions += "engine"
    productFlavors {
        create("pose") {
            dimension = "engine"
            buildConfigField("String", "ENGINE", "\"pose\"")
        }
        create("sam") {
            dimension = "engine"
            applicationIdSuffix = ".sam"
            versionNameSuffix = "-sam"
            buildConfigField("String", "ENGINE", "\"sam\"")
        }
    }

    // Part 15: release signing from the environment (CI writes the keystore from GitHub secrets; see .github/workflows/android.yml).
    // Without KEYSTORE_PATH the release build stays unsigned: CI then skips it, local builds just use assembleDebug.
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrEmpty() && file(keystorePath).isFile) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // No R8: ONNX Runtime, OpenCV and kotlinx.serialization need keep rules that were never tested here, and the APK
            // is dominated by the models and native libraries anyway.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        // The release build must not fail on lint (the first compile of this code has not happened yet); run `gradle lint` on purpose.
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // .onnx stays uncompressed: openFd() gives the size for the copy check and loading is faster.
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        // QNN/HTP must load its skel libs from disk (ADSP_LIBRARY_PATH = nativeLibraryDir): extract the .so files.
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += "**/libc++_shared.so" // ORT-QNN and OpenCV both ship it
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer) // Part 13: result preview player 
    implementation(libs.media3.transformer) // Part 14: export (hardware encoder, audio passthrough)
    implementation(libs.media3.effect) // MatrixTransformation = the follow-cam crop
    implementation(libs.opencv) // Part 8: LK optical flow for camera-motion compensation (arm64 only via abiFilters)
    implementation(libs.onnxruntime.android.qnn) // ORT with CPU + QNN (Hexagon HTP) providers; pulls com.qualcomm.qti:qnn-runtime
    debugImplementation(libs.androidx.compose.ui.tooling)
}
