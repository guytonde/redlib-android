import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versions = Properties().apply {
    rootProject.file("versions.env").reader().use { load(it) }
}

// Commit hashes are shortened to 7 characters for display.
val redlibRef: String = versions.getProperty("REDLIB_REF").let {
    if (Regex("[0-9a-f]{40}").matches(it)) it.take(7) else it
}

val redlibAbis: List<String> = providers.gradleProperty("redlib.abis").getOrElse("arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
val skipRedlibBuild: Boolean = providers.gradleProperty("redlib.skip").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "io.github.guytonde.redlib"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.guytonde.redlib"
        minSdk = versions.getProperty("ANDROID_API_LEVEL").toInt()
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0+redlib.$redlibRef"
        ndk { abiFilters += redlibAbis }
    }

    signingConfigs {
        // Sign release builds with your own key by setting these variables.
        // Without them, release builds use the debug key so the APK still installs.
        val keystore = System.getenv("REDLIB_KEYSTORE")
        if (!keystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("REDLIB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REDLIB_KEY_ALIAS")
                keyPassword = System.getenv("REDLIB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Extract native libraries on install so libredlib.so exists as a real
        // file in nativeLibraryDir and can be executed.
        jniLibs { useLegacyPackaging = true }
    }
}

val buildRedlib by tasks.registering(Exec::class) {
    group = "redlib"
    description = "Cross compiles redlib with cargo ndk into src/main/jniLibs."
    workingDir = rootDir
    commandLine("bash", rootProject.file("scripts/build-redlib.sh").absolutePath)
    environment("REDLIB_ABIS", redlibAbis.joinToString(","))
    onlyIf { !skipRedlibBuild }
}

val checkRedlib by tasks.registering {
    group = "redlib"
    description = "Fails early if a redlib binary is missing for a packaged ABI."
    mustRunAfter(buildRedlib)
    val binaries = redlibAbis.map { file("src/main/jniLibs/$it/libredlib.so") }
    doLast {
        val missing = binaries.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing ${missing.joinToString()}.\n" +
                    "Run scripts/build-redlib.sh first, or build without -Predlib.skip=true."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRedlib, checkRedlib)
}

// Copy every assembled APK to <project>/bin/redlib-android-<ref>-<variant>.apk.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val copyApk = tasks.register<Copy>("copy${capitalized}ApkToBin") {
            group = "redlib"
            description = "Copies the $variantName APK to bin/."
            from(variant.artifacts.get(SingleArtifact.APK)) { include("*.apk") }
            into(rootProject.layout.projectDirectory.dir("bin"))
            rename { "redlib-android-${redlibRef.replace('/', '-')}-$variantName.apk" }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach { finalizedBy(copyApk) }
    }
}
