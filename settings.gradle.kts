pluginManagement {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
    }
}
rootProject.name = "LostPacker"
include(":app")