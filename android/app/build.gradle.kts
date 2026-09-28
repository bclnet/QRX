plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.bclnet.qrx.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bclnet.qrx"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/examples"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":qrx-shared"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
}

// The shared example glyph documents are bundled as assets/examples/*.json.
val copyExamples by tasks.registering(Copy::class) {
    from(rootProject.file("../examples")) { include("*.json") }
    into(layout.buildDirectory.dir("generated/examples/examples"))
}
tasks.named("preBuild") { dependsOn(copyExamples) }
