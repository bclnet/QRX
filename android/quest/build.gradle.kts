plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.meta.spatial)
}

android {
    namespace = "com.bclnet.qrx.quest"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bclnet.qrx.quest"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Quest apps ship through the Meta Horizon Store, where targetSdk 32 is the supported level.
    lint { disable += "ExpiredTargetSdkVersion" }
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/examples"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":qrx-shared"))
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.meta.spatial.sdk)
    implementation(libs.meta.spatial.toolkit)
    implementation(libs.meta.spatial.vr)
    implementation(libs.meta.spatial.compose)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// The shared example glyph documents are bundled as assets/examples/*.json.
val copyExamples by tasks.registering(Copy::class) {
    from(rootProject.file("../examples")) { include("*.json") }
    into(layout.buildDirectory.dir("generated/examples/examples"))
}
tasks.named("preBuild") { dependsOn(copyExamples) }
