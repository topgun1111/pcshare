import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Sürüm: VERSION dosyası = major (2), CI her push'ta v<major>.<n> etiketini otomatik artırır (2.0, 2.1, 2.2 ...).
fun git(vararg a: String): String = try {
    val p = ProcessBuilder(listOf("git") + a).directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
} catch (e: Exception) { "" }
val commitCount = System.getenv("BUILD_N")?.toIntOrNull() ?: System.getenv("VERSION_CODE")?.toIntOrNull() ?: git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val baseVersion = File(rootDir, "VERSION").takeIf { it.exists() }?.readText()?.trim().takeUnless { it.isNullOrEmpty() } ?: "1.0"
val shortSha = git("rev-parse", "--short", "HEAD")
// Sürüm: CI release etiketinden gelir (APP_VERSION, örn. "2.3"). Yoksa: VERSION (major) + "." + commit sayısı (eski şema, eski workflow etiketiyle aynı).
val appVersion = (project.findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }   // gradle -PappVersion=2.6 (CI)
    ?: System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() }                                   // or env APP_VERSION
    ?: "${baseVersion.substringBefore('.')}.$commitCount"                                          // fallback = old scheme (major.commitCount), same as the old workflow's tag
// versionCode = minutes since 2026-01-01 (UTC): ALWAYS increases with every build, even if git history is shallow/reset.
// Never switch this scheme back to a smaller number, or Android refuses to update.
val buildCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: ((System.currentTimeMillis() / 1000 - 1767225600L) / 60).toInt()

// Edition: "full" (default) or "lite" (gradle -Pedition=lite). Lite leaves the bundled fonts out of the APK (BuildConfig.LITE = true tells the code).
val lite = (project.findProperty("edition") as String?) == "lite"
val liteExclude = listOf("fonts/office/**", "fonts/ff_*.ttf", "fonts/tnr-*.ttf", "fonts/urbanist-*.ttf")   // Office look-alike fonts, the 45 picker fonts, 2 unused families; Figtree (the default UI font) stays

android {
    namespace = "com.lanshare.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lanshare.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildCode
        versionName = appVersion + (if (shortSha.isNotEmpty()) "-$shortSha" else "") + (if (lite) "-lite" else "")
        buildConfigField("boolean", "LITE", lite.toString())
        // Image search libraries (ONNX Runtime) ship native code per CPU type; keep only 64-bit ARM (all current phones) to cut the APK by ~3/4 of that.
        ndk { abiFilters += "arm64-v8a" }
        // Lite is a SEPARATE app (own package id + name): installs next to the full one, data is not shared.
        if (lite) applicationIdSuffix = ".lite"
        manifestPlaceholders["appLabel"] = if (lite) "LANShare Lite" else "LANShare"
        manifestPlaceholders["appIcon"] = if (lite) "@mipmap/ic_launcher_lite" else "@mipmap/ic_launcher"   // lite = purple icon (res/mipmap-anydpi*/ic_launcher_lite.xml)
    }
    buildFeatures { buildConfig = true }
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
    packaging { resources { excludes += setOf("META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/OSGI-INF/MANIFEST.MF", "META-INF/versions/9/module-info.class", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") } }
    lint { checkReleaseBuilds = false; abortOnError = false }  // lintVital must never block the APK
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Lite: drop [liteExclude] from the merged assets (the source tree is untouched; CI also checks the finished APK).
if (lite) tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) doLast {
        outputs.files.forEach { d -> project.fileTree(d) { include(liteExclude) }.forEach { it.delete() } }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")      // thumbnails: EXIF rotation
    implementation("com.hierynomus:smbj:0.13.0")                      // SMB2/3 client (pure Java)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")          // MD4/RC4 for NTLM (Android's BC lacks them)
    implementation("com.github.mwiede:jsch:0.2.20")                   // SFTP (SSH) client (core/SftpFs.kt)
    implementation("commons-net:commons-net:3.9.0")                   // FTP / FTPS client (core/FtpFs.kt)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")          // Phone Explorer FTPS: self-signed certificate (pe/PeTls.kt)
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("com.github.junrar:junrar:7.5.5")                  // RAR (v4) reader, pure Java; ZIP uses java.util.zip
    implementation("org.apache.commons:commons-compress:1.21")        // 7z / TAR / GZ / BZ2 / XZ readers (1.21: works on API 24, newer ones need API 26)
    implementation("org.tukaani:xz:1.9")                              // XZ / 7z LZMA2 decoder used by commons-compress
    implementation("androidx.media3:media3-exoplayer:1.3.1")           // dedicated video player (PlayerActivity)
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")         // native file browser (BrowserActivity)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0") // pull-to-refresh in BrowserActivity
    implementation("androidx.viewpager2:viewpager2:1.0.0")             // swipe gallery of the image viewer (ImageViewerActivity)
    // image search + PaddleOCR (core/ClipEngine.kt, PaddleOcr.kt). Lite: compile-only, the runtime (~6-8 MB) is NOT in the APK; every use is behind BuildConfig.LITE.
    if (lite) compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.18.0") else implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("com.google.zxing:core:3.5.3")                       // Phone Explorer: QR pairing code (pe/PePrefs.pairingLink)
    implementation("com.google.android.gms:play-services-base:18.5.0")   // ModuleInstall: check / request the OCR model
    implementation("com.google.android.gms:play-services-auth:21.2.0")    // Google Drive sign-in: Authorization API, no client secret (core/GAuth.kt, HANDOVER chapter 17)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1") // image search: OCR via Google Play Services; model downloads on first use, then offline (core/ClipEngine.kt)
}
