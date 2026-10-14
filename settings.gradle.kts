pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://maven.kpeworld.com/releases")
            credentials {
                username = providers.gradleProperty("repo.user").orNull
                password = providers.gradleProperty("repo.password").orNull
            }
            content {
                includeGroup("io.lighthouse")
            }
        }
    }
}

rootProject.name = "GPS Phone Location Tracker"
include(":app")
include(":launcher")
