import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = gropify.project.groupName
version = gropify.project.version

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

dependencies {
    intellijPlatform {
        androidStudio(libs.versions.android.studio.get())
    }
}

intellijPlatform {
    pluginConfiguration {
        id = gropify.project.hikage.intellij.plugin.pluginId
        name = gropify.project.name
        version = gropify.project.version
        description = gropify.project.description

        ideaVersion {
            sinceBuild = libs.versions.intellij.since.build.get()
        }
    }
}