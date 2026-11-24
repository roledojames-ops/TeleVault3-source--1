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
        // TDLib (official Telegram client library) prebuilt for Android
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "TeleVault"
include(":app")
