// Root build for the QRX Android and Meta Quest apps.
//
//   qrx-core    pure Kotlin/JVM: glyph payloads, documents, lookup, BLUE/1.0 protocol
//   qrx-shared  Android library: camera + ML Kit scanning, glyph content composables, Bluetooth LE
//   app         Android phone app
//   quest       Meta Quest 3 app (Meta Spatial SDK)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.meta.spatial) apply false
}

allprojects {
    group = "com.bclnet.qrx"
    version = "1.0.0"
}
