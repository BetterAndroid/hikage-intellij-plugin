plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

group = gropify.project.groupName
version = gropify.project.version

dependencies {
    implementation(libs.hikage.gradle.model)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinpoet)

    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.jvm)
    implementation(libs.kavaref.extension)

    intellijPlatform {
        androidStudio(gropify.project.intellij.platform.android.studio.version)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.kotlin)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.android)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.gradle)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = gropify.project.pluginId
        name = gropify.project.name
        version = gropify.project.version
        description = gropify.project.description

        vendor {
            name = gropify.project.vendor.name
            email = gropify.project.vendor.email
            url = gropify.project.vendor.url
        }
        ideaVersion {
            sinceBuild = gropify.project.intellij.platform.idea.version
        }
    }
}