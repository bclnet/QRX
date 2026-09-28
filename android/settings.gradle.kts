pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QRX"

// JsonUI is a git submodule (third_party/JsonUI); its Android modules are substituted by coordinates.
includeBuild("../third_party/JsonUI/android") { name = "JsonUI" }
// JsonScene (the Scene node) is a submodule too; it reuses this build's JsonUI.
includeBuild("../third_party/TokenX/android") { name = "TokenX" }
includeBuild("../third_party/JsonMind/android") { name = "JsonMind" }
includeBuild("../third_party/JsonScene/android") { name = "JsonScene" }

include(":qrx-core")
include(":qrx-shared")
include(":app")
include(":quest")
