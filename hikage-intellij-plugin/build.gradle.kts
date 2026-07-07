plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = gropify.project.groupName
version = gropify.project.version

dependencies {
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.jvm)
    implementation(libs.kavaref.extension)

    intellijPlatform {
        androidStudio(gropify.project.intellij.platform.android.studio.version)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.kotlin)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.android)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = gropify.project.pluginId
        name = gropify.project.name
        version = gropify.project.version
        description = gropify.project.description

        ideaVersion {
            sinceBuild = gropify.project.intellij.platform.idea.version
        }
    }
}