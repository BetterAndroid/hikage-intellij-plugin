import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.2"
    id("org.jetbrains.intellij.platform.settings") version "2.17.0"
}

gropify {
    global {
        sourceCode {
            includeKeys("^project\\..*$".toRegex())
            className = "Plugin"
            isRestrictedAccessEnabled = true
        }
    }

    rootProject {
        common {
            isEnabled = false
        }
    }
}

rootProject.name = "hikage-intellij-plugin"

include(":hikage-intellij-plugin")