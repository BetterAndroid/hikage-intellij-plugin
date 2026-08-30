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
package com.highcapable.hikage.project.model.gradle

import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.resolver.PerformerDeclarationCollector
import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.indexing.PerformerSourceExcludePolicy
import com.highcapable.hikage.project.model.gradle.resolver.HikageGradleProjectResolver
import com.highcapable.hikage.project.model.gradle.tracker.ExternalSystemModelModificationTracker
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Verifies Hikage's synchronized Gradle model reader, compiler gates, and import invalidation.
 */
class GradleToolingModelRegressionTest : HikageCodeInsightTestCase() {

    private companion object {

        val descriptor = GradleToolingModels.Descriptor(
            HikageGradleModel::class,
            Key.create(classOf<HikageGradleModel>(), 0)
        )
    }

    /** Verifies custom model values are read from the official synchronized DataNode graph. */
    fun testSynchronizedModelCanBeReadForProjectAndModule() {
        val model = model(isCompilerEnabled = true)
        storeGradleModel(model)

        assertSame(model, GradleToolingModels.find(module, descriptor))
        assertEquals(listOf(model), GradleToolingModels.all(project, descriptor).toList())
    }

    /** Verifies the synchronized model graph is reused until external-system storage changes. */
    fun testSynchronizedModelGraphCacheInvalidatesWithStorage() {
        val initialModel = model(isCompilerEnabled = true)
        storeGradleModel(initialModel)
        val initialModels = GradleToolingModels.all(project, descriptor)

        assertSame(initialModels, GradleToolingModels.all(project, descriptor))
        assertSame(initialModel, GradleToolingModels.find(module, descriptor))

        val updatedModel = model(isCompilerEnabled = false)
        storeGradleModel(updatedModel)
        val updatedModels = GradleToolingModels.all(project, descriptor)

        assertNotSame(initialModels, updatedModels)
        assertEquals(listOf(updatedModel), updatedModels)
        assertSame(updatedModel, GradleToolingModels.find(module, descriptor))
    }

    /** Verifies compiler-disabled modules cannot publish annotation-backed performer declarations. */
    fun testCompilerGateControlsAnnotationDeclarations() {
        installHikageTestApi()
        val viewFile = addProjectFile(
            "sample/GatedView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView
            class GatedView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        storeGradleModel(model(isCompilerEnabled = false))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue(PerformerDeclarationCollector.from(project).collect().isEmpty())

        storeGradleModel(model(isCompilerEnabled = true))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val declarations = PerformerDeclarationCollector.from(project).collect()

        assertEquals(listOf("sample.GatedView"), declarations.map(PerformerDeclaration::viewClass))
        assertEquals(module, ModuleUtilCore.findModuleForPsiElement(viewFile))
    }

    /** Verifies an Android source-set module selects its owning model instead of another module under the same root. */
    fun testSourceSetModuleSelectsOwningModelAcrossSharedGradleRoot() {
        val expected = model(isCompilerEnabled = true)
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        projectNode.createChild(
            ProjectKeys.MODULE,
            ModuleData(
                "com.highcapable.hikage.fixture.app",
                GradleConstants.SYSTEM_ID,
                "JAVA_MODULE",
                "hikage-fixture-app",
                rootPath,
                rootPath
            )
        ).createChild(descriptor.key, model(isCompilerEnabled = false))
        val widgetModuleData = ModuleData(
            "com.highcapable.hikage.fixture.widget",
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            "hikage-fixture-widget",
            rootPath,
            rootPath
        )
        val widgetModuleNode = projectNode.createChild(ProjectKeys.MODULE, widgetModuleData)
        widgetModuleNode.createChild(descriptor.key, expected)
        val sourceSetData = GradleSourceSetData(
            "com.highcapable.hikage.fixture.widget:main",
            "hikage-fixture-widget:main",
            "hikage-fixture-widget.main",
            rootPath,
            rootPath
        )
        widgetModuleNode.createChild(GradleSourceSetData.KEY, sourceSetData)
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            sourceSetData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )

        assertSame(expected, GradleToolingModels.find(module, descriptor))
    }

    /** Verifies a source-set module without a model does not inherit one from a sibling with the same Gradle root. */
    fun testSourceSetModuleDoesNotInheritSiblingModelAcrossSharedGradleRoot() {
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        projectNode.createChild(
            ProjectKeys.MODULE,
            ModuleData(
                "com.highcapable.hikage.test.sibling",
                GradleConstants.SYSTEM_ID,
                "JAVA_MODULE",
                "hikage-test-sibling",
                rootPath,
                rootPath
            )
        ).createChild(descriptor.key, model(isCompilerEnabled = true))
        val owningModuleData = ModuleData(
            "com.highcapable.hikage.test.owner",
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            "hikage-test-owner",
            rootPath,
            rootPath
        )
        val owningModuleNode = projectNode.createChild(ProjectKeys.MODULE, owningModuleData)
        val sourceSetData = GradleSourceSetData(
            "com.highcapable.hikage.test.owner:main",
            "hikage-test-owner:main",
            "hikage-test-owner.main",
            rootPath,
            rootPath
        )
        owningModuleNode.createChild(GradleSourceSetData.KEY, sourceSetData)
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            sourceSetData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )

        assertNull(GradleToolingModels.find(module, descriptor))
    }

    /** Verifies strict input JSON is used when Gradle has not produced its generated declaration output yet. */
    fun testStrictInputJsonProvidesPreBuildFallback() {
        installHikageTestApi()
        addProjectFile(
            "sample/FallbackView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class FallbackView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val input = createTempFile("hikage-declarations-", ".json")
        input.writeText(
            """
            [
              {
                "viewClass": "sample.FallbackView",
                "alias": "Fallback"
              }
            ]
            """.trimIndent()
        )
        LocalFileSystem.getInstance().refreshAndFindFileByPath(input.toString())
        try {
            storeGradleModel(
                model(
                    isCompilerEnabled = true,
                    strictViewDeclarationInputFiles = listOf(input.toString())
                )
            )

            val declaration = PerformerDeclarationCollector.from(project).collect().single()
            assertEquals("sample.FallbackView", declaration.viewClass)
            assertEquals("Fallback", declaration.functionName)
            assertEquals(PerformerDeclaration.Source.STRICT_FILE, declaration.source)
        } finally {
            input.deleteIfExists()
        }
    }

    /** Verifies stale generated JSON cannot outlive the synchronized dependency artifact that supplied it. */
    fun testSynchronizedInputsOverrideStaleGeneratedOutput() {
        installHikageTestApi()
        addProjectFile(
            "androidx/appcompat/widget/AppCompatImageView.kt",
            """
            package androidx.appcompat.widget

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class AppCompatImageView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val output = createTempDirectory("hikage-stale-declarations-")
        output.resolve("appcompat.json").writeText(
            """
            [
              {
                "viewClass": "androidx.appcompat.widget.AppCompatImageView"
              }
            ]
            """.trimIndent()
        )
        LocalFileSystem.getInstance().refreshAndFindFileByPath(output.toString())
        try {
            storeGradleModel(
                model(
                    isCompilerEnabled = true,
                    optionalViewDeclarationFiles = listOf(output.toString())
                )
            )
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            assertEmpty(PerformerDeclarationCollector.from(project).collect())
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    /** Verifies a synchronized dependency artifact still supplies its current optional declarations. */
    fun testOptionalInputArtifactProvidesDependencyDeclarations() {
        installHikageTestApi()
        addProjectFile(
            "sample/DependencyView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class DependencyView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val artifact = createTempFile("hikage-declarations-", ".jar")
        ZipOutputStream(artifact.toFile().outputStream()).use { archive ->
            archive.putNextEntry(ZipEntry("META-INF/hikage/view-declaration/dependency.json"))
            archive.write(
                """
                [
                  {
                    "viewClass": "sample.DependencyView"
                  }
                ]
                """.trimIndent().encodeToByteArray()
            )
            archive.closeEntry()
        }
        try {
            storeGradleModel(
                model(
                    isCompilerEnabled = true,
                    optionalViewDeclarationInputArtifacts = listOf(artifact.toString())
                )
            )
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            val declaration = PerformerDeclarationCollector.from(project).collect().single()
            assertEquals("sample.DependencyView", declaration.viewClass)
            assertEquals(PerformerDeclaration.Source.OPTIONAL_FILE, declaration.source)
        } finally {
            artifact.deleteIfExists()
        }
    }

    /** Verifies the finalized Gradle graph excludes only Hikage's generated KSP widget subtree. */
    fun testFinalizedGeneratedKspExclusionIsScopedToHikageWidgetPackage() {
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val moduleData = ModuleData(
            module.name,
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            module.name,
            rootPath,
            rootPath
        )
        val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
        moduleNode.createChild(descriptor.key, model(isCompilerEnabled = true))
        val contentRoot = ContentRootData(GradleConstants.SYSTEM_ID, rootPath)
        val generatedKsp = Path.of(rootPath, "build", "generated", "ksp", "debug", "kotlin").toString()
        val unrelatedGenerated = Path.of(rootPath, "build", "generated", "source", "debug", "kotlin").toString()
        contentRoot.storePath(ExternalSystemSourceType.SOURCE_GENERATED, generatedKsp)
        contentRoot.storePath(ExternalSystemSourceType.SOURCE_GENERATED, unrelatedGenerated)
        moduleNode.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)

        HikageGradleProjectResolver().resolveFinished(projectNode)

        val excluded = contentRoot.getPaths(ExternalSystemSourceType.EXCLUDED).map { source -> source.path }
        assertEquals(
            listOf(Path.of(generatedKsp, "com", "highcapable", "hikage", "widget").toString()),
            excluded
        )
    }

    /** Verifies cached KSP sources remain compatible without a model and are excluded once the model appears. */
    fun testGeneratedKspCompatibilitySourceFollowsToolingModelAvailability() {
        storeGradleModel(null)
        val fixtureRoot = createTempDirectory("hikage-ksp-source-scope-")
        val generatedKspPath = fixtureRoot.resolve("build/generated/ksp/debug/kotlin").createDirectories()
        val performerPath = generatedKspPath
            .resolve("com/highcapable/hikage/widget/fixture/FixtureView.kt")
        performerPath.parent.createDirectories()
        performerPath.writeText(
            """
            package com.highcapable.hikage.widget.fixture

            fun FixtureView() = Unit
            """.trimIndent()
        )
        val unrelatedPath = generatedKspPath.resolve("com/highcapable/hikage/generated/Unrelated.kt")
        unrelatedPath.parent.createDirectories()
        unrelatedPath.writeText(
            """
            package com.highcapable.hikage.generated

            fun Unrelated() = Unit
            """.trimIndent()
        )
        val refreshSourcePath = fixtureRoot.resolve("refresh").createDirectories()
        val localFileSystem = LocalFileSystem.getInstance()
        val fixtureRootFile = requireNotNull(localFileSystem.refreshAndFindFileByPath(fixtureRoot.toString()))
        val generatedKsp = requireNotNull(localFileSystem.findFileByPath(generatedKspPath.toString()))
        val performerFile = requireNotNull(localFileSystem.findFileByPath(performerPath.toString()))
        val unrelatedFile = requireNotNull(localFileSystem.findFileByPath(unrelatedPath.toString()))
        val refreshSource = requireNotNull(localFileSystem.findFileByPath(refreshSourcePath.toString()))

        try {
            addContentSourceRoot(fixtureRootFile, generatedKsp)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val policy = DirectoryIndexExcludePolicy.getExtensions(project)
                .filterIsInstance<PerformerSourceExcludePolicy>()
                .single()
            val fileIndex = ProjectFileIndex.getInstance(project)

            assertEmpty(policy.excludeUrlsForProject)
            assertTrue(fileIndex.isInSource(performerFile))
            assertFalse(fileIndex.isExcluded(performerFile))

            storeGradleModel(model(isCompilerEnabled = true))
            addSourceRoot(fixtureRootFile, refreshSource)
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            assertEquals(
                listOf(
                    VfsUtilCore.pathToUrl(
                        Path.of(generatedKsp.path, "com", "highcapable", "hikage", "widget").toString()
                    )
                ),
                policy.excludeUrlsForProject.toList()
            )
            assertTrue(fileIndex.isExcluded(performerFile))
            assertFalse(fileIndex.isInSource(performerFile))
            assertFalse(fileIndex.isExcluded(unrelatedFile))
            assertTrue(fileIndex.isInSource(unrelatedFile))
        } finally {
            removeContentRoot(fixtureRootFile)
            fixtureRoot.toFile().deleteRecursively()
        }
    }

    /** Verifies a completed external-system import invalidates the model snapshot dependency. */
    fun testCompletedImportAdvancesModelModificationTracker() {
        val tracker = ExternalSystemModelModificationTracker.getInstance(project)
        val before = tracker.modificationCount

        project.messageBus.syncPublisher(ProjectDataImportListener.TOPIC)
            .onImportFinished(project.basePath)

        assertEquals(before + 1, tracker.modificationCount)
    }

    private fun storeGradleModel(model: HikageGradleModel?) {
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val moduleData = ModuleData(
            module.name,
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            module.name,
            rootPath,
            rootPath
        )
        val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
        model?.let { moduleNode.createChild(descriptor.key, it) }
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            moduleData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )
    }

    private fun addContentSourceRoot(contentRoot: VirtualFile, sourceRoot: VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { rootModel ->
            rootModel.addContentEntry(contentRoot).addSourceFolder(sourceRoot, false)
        }
    }

    private fun addSourceRoot(contentRoot: VirtualFile, sourceRoot: VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { rootModel ->
            rootModel.contentEntries.single { entry -> entry.file == contentRoot }
                .addSourceFolder(sourceRoot, false)
        }
    }

    private fun removeContentRoot(contentRoot: VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { rootModel ->
            rootModel.contentEntries.singleOrNull { entry -> entry.file == contentRoot }
                ?.let(rootModel::removeContentEntry)
        }
    }

    private fun model(
        isCompilerEnabled: Boolean,
        viewDeclarationFiles: List<String> = emptyList(),
        optionalViewDeclarationFiles: List<String> = emptyList(),
        strictViewDeclarationInputFiles: List<String> = emptyList(),
        optionalViewDeclarationInputArtifacts: List<String> = emptyList()
    ) = DefaultHikageGradleModel(
        isPluginApplied = true,
        isCompilerEnabled = isCompilerEnabled,
        viewDeclarationFiles = viewDeclarationFiles,
        optionalViewDeclarationFiles = optionalViewDeclarationFiles,
        strictViewDeclarationInputFiles = strictViewDeclarationInputFiles,
        optionalViewDeclarationInputArtifacts = optionalViewDeclarationInputArtifacts
    )

    private class StoredExternalProjectInfo(
        private val systemId: ProjectSystemId,
        private val path: String,
        private val structure: DataNode<ProjectData>
    ) : ExternalProjectInfo {
        override fun getProjectSystemId() = systemId
        override fun getExternalProjectPath() = path
        override fun getExternalProjectStructure() = structure
        override fun getLastSuccessfulImportTimestamp() = 1L
        override fun getLastImportTimestamp() = 1L
        override fun getBuildNumber() = ""
        override fun copy() = StoredExternalProjectInfo(systemId, path, structure.graphCopy())
    }
}