/*
 * Hikage - A real-time Android View runtime powered by Kotlin DSL.
 * Copyright (C) 2019 HighCapable
 * https://github.com/BetterAndroid/Hikage
 *
 * Apache License Version 2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is created by fankes on 2026/7/24.
 */
package com.highcapable.hikage.integration

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.AnAction
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.Notification
import com.intellij.driver.sdk.getContentEntries
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForOne
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.runIdeTest
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * Verifies Hikage's Android Studio recommendation and dependency editing flows against a real AGP project.
 */
class HikageGradleIntegrationTest {

    private companion object {

        const val GROUP = "com.highcapable.hikage"
        const val PLUGIN_ID = GROUP
        const val NOTIFICATION_GROUP_ID = "Hikage Recommendations"

        const val BOM_ARTIFACT = "hikage-bom"
        const val EXTENSION_BETTERANDROID_ARTIFACT = "hikage-extension-betterandroid"
        const val RUNTIME_ATTRIBUTE_ARTIFACT = "hikage-runtime-attribute"
        const val RUNTIME_ATTRIBUTE_MODULE = "$GROUP:$RUNTIME_ATTRIBUTE_ARTIFACT"
        const val FALLBACK_BOM = "$GROUP:$BOM_ARTIFACT:1.1.1-debug"

        const val DEFAULT_CONFIGURATION = "implementation"

        const val TEST_DATA_PROPERTY = "hikage.integration.test.data"
        const val ROOT_PROJECT_PROPERTY = "hikage.integration.root.project"
        const val IDE_PATH_PROPERTY = "hikage.integration.ide.path"
        const val PLUGIN_PATH_PROPERTY = "path.to.build.plugin"

        val STANDARD_ARTIFACTS = listOf(
            "hikage-core",
            "hikage-extension",
            "hikage-widget-foundation",
            "hikage-widget-androidx",
            "hikage-widget-material"
        )
    }

    /**
     * Verifies Gradle Sync triggers the recommendation, its action installs the standard BOM-managed stack together
     * with the detected BetterAndroid integration, and the runtime-attribute dependency path preserves the BOM.
     */
    @Test
    fun recommendationAndDependencyQuickFixUseAndroidStudioGradleModels() {
        val projectDirectory = prepareProject()
        val (idePath, ideWrapper) = prepareIdePath()
        try {
            val ideInfo = IdeProductProvider.AI.copy(
                getInstaller = { ExistingIdeInstaller(idePath) }
            )
            val testName = "hikage-gradle-recommendation"
            val context = Starter.newContext(testName, TestCase(ideInfo, LocalProjectInfo(projectDirectory)))
                .prepareProjectCleanImport()
                .addProjectToTrustedLocations(projectDirectory.toRealPath())
                .disableAIAssistantToolwindowActivationOnStart()
                .disablePackageSearchBuildFiles()
            context.pluginConfigurator.installPluginFromPath(Path.of(requireProperty(PLUGIN_PATH_PROPERTY)))

            context.runIdeTest(testName, timeout = 15.minutes) {
                waitForProjectOpen()
                val project = singleProject()
                val recommendation = waitForOne(
                    message = "Hikage recommendation",
                    timeout = 10.minutes,
                    getter = { getNotifications(project) },
                    checker = { notification -> notification.getGroupId() == NOTIFICATION_GROUP_ID }
                )
                assertTrue(recommendation.getTitle().isNotBlank())
                assertTrue(recommendation.getContent().isNotBlank())
                val recommendationAction = recommendation.getActions().single()

                withContext(OnDispatcher.EDT, LockSemantics.READ_ACTION) {
                    utility<RemoteNotification>().fire(recommendation, recommendationAction, null)
                }

                val catalogFile = projectDirectory.resolve("gradle/libs.versions.toml")
                val buildFile = projectDirectory.resolve("app/build.gradle.kts")
                waitFor(message = "standard Hikage Gradle configuration", timeout = 2.minutes) {
                    runCatching {
                        catalogFile.readText().contains("hikagePlugin") &&
                            catalogFile.readText().contains(EXTENSION_BETTERANDROID_ARTIFACT) &&
                            buildFile.readText().contains("libs.plugins.hikage")
                    }.getOrDefault(false)
                }

                val appDirectory = projectDirectory.resolve("app").toRealPath()
                val importedModules = getModules(project)
                val appModule = importedModules.firstOrNull { module ->
                    getContentEntries(module).any { contentEntry ->
                        Path.of(contentEntry.getFile().getPath()).toRealPath() == appDirectory
                    }
                } ?: error("Android app module was not imported: ${importedModules.map(Module::getName)}")
                val isAdded = service<RemoteGradleDependencyService>(project).addDependency(
                    appModule,
                    RUNTIME_ATTRIBUTE_MODULE,
                    DEFAULT_CONFIGURATION,
                    FALLBACK_BOM
                )
                assertTrue(isAdded)
                waitFor(message = "runtime-attribute dependency", timeout = 2.minutes) {
                    catalogFile.readText().contains(RUNTIME_ATTRIBUTE_ARTIFACT)
                }

                val catalog = catalogFile.readText()
                val build = buildFile.readText()
                assertTrue(catalog.contains("hikagePlugin"))
                assertTrue(catalog.contains("id = \"$PLUGIN_ID\""))
                assertTrue(catalog.hasArtifact(BOM_ARTIFACT))
                STANDARD_ARTIFACTS.forEach { artifact ->
                    assertTrue(catalog.hasVersionlessArtifact(artifact), "$artifact must remain BOM-managed:\n$catalog")
                }
                assertTrue(
                    catalog.hasVersionlessArtifact(EXTENSION_BETTERANDROID_ARTIFACT),
                    "$EXTENSION_BETTERANDROID_ARTIFACT must be added for BetterAndroid's adapter:\n$catalog"
                )
                assertTrue(
                    catalog.hasVersionlessArtifact(RUNTIME_ATTRIBUTE_ARTIFACT),
                    "$RUNTIME_ATTRIBUTE_ARTIFACT must remain BOM-managed:\n$catalog"
                )
                assertTrue(build.contains("alias(libs.plugins.hikage)"))
                assertTrue(build.contains("implementation(platform(libs."))
                assertTrue(build.contains("implementation(libs."))
            }
        } finally {
            projectDirectory.toFile().deleteRecursively()
            ideWrapper?.toFile()?.deleteRecursively()
        }
    }

    private fun prepareProject(): Path {
        val source = Path.of(requireProperty(TEST_DATA_PROPERTY), "recommendation")
        val projectDirectory = createTempDirectory("hikage-gradle-integration-")
        source.toFile().copyRecursively(projectDirectory.toFile(), overwrite = true)

        val rootProject = Path.of(requireProperty(ROOT_PROJECT_PROPERTY))
        listOf(
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties"
        ).forEach { relativePath ->
            val target = projectDirectory.resolve(relativePath)
            target.parent.createDirectories()
            rootProject.resolve(relativePath).copyTo(target, overwrite = true)
        }
        projectDirectory.resolve("gradlew").toFile().setExecutable(true)
        projectDirectory.resolve("local.properties").writeText("sdk.dir=${findAndroidSdk()}\n")
        return projectDirectory
    }

    private fun prepareIdePath(): Pair<Path, Path?> {
        val configuredPath = Path.of(requireProperty(IDE_PATH_PROPERTY))
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ||
            configuredPath.fileName.toString().endsWith(".app")
        ) return configuredPath to null

        require(configuredPath.resolve("Info.plist").exists()) {
            "Android Studio's macOS Contents directory was not found at $configuredPath"
        }
        val wrapper = createTempDirectory("hikage-android-studio-")
        val app = wrapper.resolve("Android Studio.app").createDirectories()
        // Gradle exposes the macOS distribution as Contents instead of a launch-trusted app bundle. Preserve its
        // symlinks and metadata with ditto, then ad-hoc sign only this temporary sandbox copy for Gatekeeper.
        runProcess("/usr/bin/ditto", configuredPath.toString(), app.resolve("Contents").toString())
        runProcess("/usr/bin/xattr", "-cr", app.toString())
        runProcess("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app.toString())
        return app to wrapper
    }

    private fun runProcess(vararg command: String) {
        val process = ProcessBuilder(*command).inheritIO().start()
        check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
    }

    private fun findAndroidSdk(): Path {
        val environment = listOfNotNull(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME")
        ).map(Path::of)
        val userHome = Path.of(System.getProperty("user.home"))
        return (environment + listOf(
            userHome.resolve("Library/Android/sdk"),
            userHome.resolve("Android/Sdk")
        )).firstOrNull(Path::exists) ?: error("Android SDK was not found")
    }

    private fun String.hasArtifact(artifact: String) = lineSequence().any { line ->
        line.contains("$GROUP:$artifact") || line.contains("name = \"$artifact\"")
    }

    private fun String.hasVersionlessArtifact(artifact: String) = lineSequence().any { line ->
        (line.contains("$GROUP:$artifact") || line.contains("name = \"$artifact\"")) &&
            !line.contains("version")
    }

    private fun requireProperty(name: String) = requireNotNull(System.getProperty(name)) {
        "Missing integration-test system property '$name'"
    }

    @Remote(
        value = "com.highcapable.hikage.project.GradleDependencyService",
        plugin = PLUGIN_ID
    )
    private interface RemoteGradleDependencyService {

        fun addDependency(
            module: Module,
            coordinate: String,
            configuration: String,
            platformCoordinate: String?
        ): Boolean
    }

    @Remote("com.intellij.openapi.actionSystem.DataContext")
    private interface RemoteDataContext

    @Remote("com.intellij.notification.Notification")
    private interface RemoteNotification {

        fun fire(notification: Notification, action: AnAction, dataContext: RemoteDataContext?)
    }
}