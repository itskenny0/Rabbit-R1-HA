import java.util.Properties
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.itskenny0.r1ha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.itskenny0.r1ha"
        minSdk = 33
        targetSdk = 34
        // Versions are date-based to match the `r1ha-YYYYMMDD` release tag scheme.
        // CI passes APP_VERSION_CODE / APP_VERSION_NAME on tag builds; local builds fall back to today's date.
        versionCode = (System.getenv("APP_VERSION_CODE") ?: defaultVersionCode()).toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: defaultVersionName()

        // BuildConfig fields surfaced in the About screen
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/itskenny0/Rabbit-R1-HA\"")
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            // Stable debug keystore committed at repo root so every CI release
            // signs with the same SHA-1 and `adb install -r` works for updates.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release config is read from local.properties / gradle.properties if present.
        // If not present, release builds will fail explicitly rather than ship unsigned.
        val keystorePropsFile = rootProject.file("local.properties")
        if (keystorePropsFile.exists()) {
            val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
            if (props.getProperty("RELEASE_STORE_FILE") != null) {
                create("release") {
                    storeFile = file(props.getProperty("RELEASE_STORE_FILE"))
                    storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                    keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                    keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.useJUnitPlatform() }
        }
    }

    lint {
        // androidx.lifecycle's NonNullableMutableLiveDataDetector crashes during lint
        // analysis with the current AGP/Kotlin combo (IncompatibleClassChangeError on
        // KaCallableMemberCall — the detector was compiled against a different version of
        // the Kotlin compiler analysis API than what AGP ships). This app doesn't use
        // MutableLiveData, so the detector has nothing to do. Disable it and don't fail the
        // build on lint-internal errors that fall outside our actual code.
        disable += setOf("NullSafeMutableLiveData")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // Explicit compose.foundation — material3 used to pull it in transitively but
    // we now reach into the gesture-detector / pointer-input API surface for the
    // touch-drag slider on the vertical tape meter, which lives in foundation only.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.test.junit.jupiter)
    testImplementation(libs.test.junit.jupiter.params)
    testImplementation(libs.test.turbine)
    testImplementation(libs.test.truth)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

fun gitSha(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir).redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().readText().trim().ifEmpty { "dev" }
} catch (_: Exception) { "dev" }

/**
 * Default for local dev: a fixed 100M floor + minutes since 2020-01-01 UTC. The 100M
 * floor exists purely so this scheme produces versionCodes that are strictly larger
 * than every previously-shipped legacy YYYYMMDD-as-Int value (a 2026 release was
 * 20_260_513; ten-million-range vs. one-hundred-million-range means upgrade-on-top-of
 * never trips Android's "version code is older or same" rejection, which the OS
 * surfaces as the confusing "App not installed as package appears to be invalid"
 * message). Floor + minutes-since-epoch stays strictly monotonic with wall-clock
 * time and fits inside Int range until roughly year 4000 — plenty of runway.
 */
fun defaultVersionCode(): String {
    // 100M floor (see comment above) + minutes since 2020-01-01 UTC. Every legacy
    // YYYYMMDD-as-Int we ever shipped fits well below 100M (max plausible:
    // 99_991_231 in year 9999), so adding this floor guarantees strict monotonic
    // upgrade no matter which scheme was used for the previously-installed APK.
    val floor = 100_000_000L
    val epoch = LocalDateTime.of(2020, 1, 1, 0, 0)
    val now = LocalDateTime.now(ZoneOffset.UTC)
    val minutes = Duration.between(epoch, now).toMinutes()
    return (floor + minutes).coerceAtLeast(1).toString()
}

/**
 * Default for local dev: `YYYY.MM.DD.HHmm` in UTC. Same-day reships then display
 * distinct, human-readable versions (2026.05.13.1430 vs 2026.05.13.1820) instead of
 * Android adding a transparent letter suffix when two installs collide.
 */
fun defaultVersionName(): String =
    LocalDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmm"))
