import java.util.Properties
import java.time.LocalDate
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
            // Android Studio default debug keystore — auto-generated, not committed
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

/** Default for local dev: YYYYMMDD as an Int (e.g. 20260511). */
fun defaultVersionCode(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

/** Default for local dev: YYYY.MM.DD (human-friendly). */
fun defaultVersionName(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
