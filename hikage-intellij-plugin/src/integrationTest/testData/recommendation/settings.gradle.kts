pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("repository") }
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "hikage-integration"
include(":app")