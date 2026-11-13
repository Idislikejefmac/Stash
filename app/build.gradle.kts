import java.io.StringReader
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Card 26d: the speech engine, sherpa-onnx 2.0.1 (Apache-2.0) with ONNX Runtime inside it (MIT); docs/LICENSES.md.
// The AAR stays OUTSIDE the repository. Its path is the property `stash.sherpaAar`, from -P, gradle.properties or
// local.properties (in that order). Not given, or given empty (-Pstash.sherpaAar=): the app builds without the engine
// (src/nosherpa/, NoTranscriber) and every test still runs. Given: the file must exist and have exactly this sha256,
// or the build stops with a plain message.
val sherpaAarSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar: File? = run {
    val fromGradle = providers.gradleProperty("stash.sherpaAar").orNull
    val fromLocal = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(StringReader(text)) }.getProperty("stash.sherpaAar") }
    (fromGradle ?: fromLocal)?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
}
if (sherpaAar != null && !sherpaAar.isFile) {
    throw GradleException(
        "stash.sherpaAar says the speech engine is at $sherpaAar, but there is no file there. Put sherpa-onnx-2.0.1.aar " +
            "there (sha256 $sherpaAarSha256), or build without the engine with -Pstash.sherpaAar= (empty).",
    )
}

android {
    namespace = "app.stash"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.stash"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-prototype"
        // Card 26d: arm64 libraries only, in every build type (the engine's libraries are 31.4 MB per ABI).
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Card 26d: the engine adapter when the AAR is given; otherwise the stand-in that has no engine.
    sourceSets.getByName("main").kotlin.srcDir(if (sherpaAar != null) "src/sherpa/java" else "src/nosherpa/java")
    // Card 25 spike (throwaway, debug only): still compiled when the engine is, as before. Its ML Kit GenAI part was
    // removed with that library by card 26d; src/debug/spike25-nogenai/ stands in for Spike25MlKit (see below).
    if (sherpaAar != null) {
        sourceSets.getByName("debug").kotlin.srcDir("src/debug/spike25/java")
        sourceSets.getByName("debug").kotlin.srcDir("src/debug/spike25-nogenai/java")
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources; returnDefaultValues is deliberately not set.
            isIncludeAndroidResources = true
        }
    }
}

// Room writes one JSON per database version here; commit them (docs/cards/01).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Card 26d: the AAR's sha256 is checked before anything is built from it.
val verifySherpaAar = tasks.register("verifySherpaAar") {
    val aar = sherpaAar
    val expected = sherpaAarSha256
    if (aar != null) inputs.file(aar)
    doLast {
        if (aar == null) return@doLast
        val md = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException(
                "The speech engine file $aar is not sherpa-onnx-2.0.1.aar: its sha256 is $actual, expected $expected. " +
                    "Download it again from github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1.",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySherpaAar) }

// Card 25 spike: Spike25MlKit.kt needed the ML Kit GenAI library, which card 26d removed; a stand-in replaces it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/Spike25MlKit.kt")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Room: the local SQLite database the brief asks for.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Background extraction (card 13): the one new dependency.
    implementation(libs.androidx.work.runtime.ktx)
    // Card 16a: per-file AES-256-GCM streaming encryption of attachments (Tink 1.24.0, Apache-2.0, docs/LICENSES.md).
    implementation(libs.tink.android)
    // Card 16e: the in-app camera (CameraX 1.6.2, Apache-2.0 per its POMs, docs/LICENSES.md).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Card 16f: ML Kit Text Recognition v2 (Latin, bundled model). Google's own terms, not an open licence (D78, D80);
    // it reports usage figures to Google (disclosed in Settings). docs/LICENSES.md.
    implementation(libs.mlkit.text.recognition)
    // Card 26d: sherpa-onnx 2.0.1 (Apache-2.0) with ONNX Runtime (MIT), every build type, arm64 only (abiFilters
    // above); from outside the repository (stash.sherpaAar). docs/LICENSES.md.
    if (sherpaAar != null) implementation(files(sherpaAar))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android's org.json is a stub in local unit tests; use the real implementation.
    testImplementation(libs.org.json)
    // Real Room and real SQLite on the JVM for repository and migration tests (card 01); test-only.
    testImplementation(libs.robolectric)
    // Card 05: a Robolectric test draws the "To handle" screen from a seeded database. Test-only.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
