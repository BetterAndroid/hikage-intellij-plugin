import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

group = gropify.project.groupName
version = gropify.project.version

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(gropify.project.android.lint.mirror.kotlin.sourceDir)
        }
    }
}

sourceSets {
    main {
        resources.srcDir(gropify.project.android.lint.mirror.resourcesDir)
    }
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation = configurations.getByName("integrationTestImplementation").apply {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    implementation(libs.hikage.gradle.model)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinpoet)

    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.jvm)
    implementation(libs.kavaref.extension)

    testImplementation(libs.junit)
    integrationTestImplementation(libs.junit.jupiter)
    integrationTestImplementation(libs.kodein.di)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)

    intellijPlatform {
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.kotlin)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.android)
        bundledPlugin(gropify.project.intellij.platform.bundled.plugin.gradle)
        androidStudio(gropify.project.intellij.platform.android.studio.version)

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
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

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath

        systemProperty(
            gropify.project.testing.intellijPlatformTesting.system.property.test.data.key,
            project.layout.projectDirectory.dir(gropify.project.testing.intellijPlatformTesting.test.data.path).asFile.absolutePath
        )
        systemProperty(
            gropify.project.testing.intellijPlatformTesting.system.property.root.project.key,
            project.rootProject.layout.projectDirectory.asFile.absolutePath
        )

        doFirst {
            systemProperty(
                gropify.project.testing.intellijPlatformTesting.system.property.ide.path.key,
                platformPath.toString()
            )
        }
        useJUnitPlatform()
    }
}