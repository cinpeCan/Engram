import java.io.StringReader
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

val localPropertiesText = providers.fileContents(layout.settingsDirectory.file("local.properties")).asText.orNull
val localPropertiesCloudUrl = localPropertiesText?.let { raw ->
    Properties().apply { load(StringReader(raw)) }
        .getProperty("ENGRAM_DEFAULT_CLOUD_BASE_URL")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
val defaultCloudBaseUrl = (
    localPropertiesCloudUrl
        ?: providers.gradleProperty("ENGRAM_DEFAULT_CLOUD_BASE_URL").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("ENGRAM_DEFAULT_CLOUD_BASE_URL").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""
    ).trimEnd('/')

val (configSeedAHex, configSeedBHex, configNonceHex, configPayloadHex) = if (defaultCloudBaseUrl.isNotEmpty()) {
    val rng = SecureRandom()
    val maskA = ByteArray(32).also { rng.nextBytes(it) }
    val secretKey = ByteArray(32).also { rng.nextBytes(it) }
    val maskB = ByteArray(32) { idx -> (secretKey[idx].toInt() xor maskA[idx].toInt()).toByte() }
    val iv = ByteArray(12).also { rng.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, iv))
    val cipherAndTag = cipher.doFinal(defaultCloudBaseUrl.toByteArray(Charsets.UTF_8))
    secretKey.fill(0)
    listOf(maskA.toHexString(), maskB.toHexString(), iv.toHexString(), cipherAndTag.toHexString())
} else {
    listOf("", "", "", "")
}

android {
    namespace = "io.github.cinpecan.engram"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.cinpecan.engram"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CONFIG_SEED_A", "\"$configSeedAHex\"")
        buildConfigField("String", "CONFIG_SEED_B", "\"$configSeedBHex\"")
        buildConfigField("String", "CONFIG_NONCE", "\"$configNonceHex\"")
        buildConfigField("String", "CONFIG_PAYLOAD", "\"$configPayloadHex\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":packages:edge-mnn"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.navigation3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.deponder.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
