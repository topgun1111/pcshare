import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
// Sürüm: VERSION dosyası (major.minor) + git commit sayısı. Dosya ezilse bile kod geri sarmaz.
fun git(vararg a: String): String = try {
    val p = ProcessBuilder(listOf("git") + a).directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
} catch (e: Exception) { "" }
val commitCount = System.getenv("VERSION_CODE")?.toIntOrNull() ?: git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val baseVersion = File(rootDir, "VERSION").takeIf { it.exists() }?.readText()?.trim().takeUnless { it.isNullOrEmpty() } ?: "1.0"
val shortSha = git("rev-parse", "--short", "HEAD")

android {
    namespace = "com.lanshare.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lanshare.app"
        minSdk = 24
        targetSdk = 34
        versionCode = commitCount
        versionName = "$baseVersion.$commitCount" + (if (shortSha.isNotEmpty()) "-$shortSha" else "")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // Sabit imza: her derlemede AYNI anahtar kullanılır, böylece güncellemeler üstüne kurulur.
    // CI secret'ları (KEYSTORE_FILE...) verilirse onlar, yoksa depodaki app/lanshare.jks kullanılır.
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null && File(ks).exists()) {
                storeFile = File(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("lanshare.jks")
                storePassword = "lanshare123"
                keyAlias = "lanshare"
                keyPassword = "lanshare123"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
chaquopy { defaultConfig { version = "3.11" } }
dependencies { implementation("androidx.core:core-ktx:1.13.1") }
