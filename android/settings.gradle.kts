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
includeBuild("../third_party/JsonUI/android")

include(":qrx-core")
include(":qrx-shared")
include(":app")
include(":quest")
