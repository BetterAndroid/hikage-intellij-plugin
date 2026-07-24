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
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
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
        assertTrue(PerformerDeclarationCollector.from(project).collect().isEmpty())

        storeGradleModel(model(isCompilerEnabled = true))
        val declarations = PerformerDeclarationCollector.from(project).collect()

        assertEquals(listOf("sample.GatedView"), declarations.map(PerformerDeclaration::viewClass))
        assertEquals(module, ModuleUtilCore.findModuleForPsiElement(viewFile))
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

    /** Verifies only Hikage's generated KSP widget subtree is excluded from IDE source roots. */
    fun testGeneratedKspExclusionIsScopedToHikageWidgetPackage() {
        val rootPath = requireNotNull(project.basePath)
        val moduleData = ModuleData(
            module.name,
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            module.name,
            rootPath,
            rootPath
        )
        val moduleNode = DataNode(ProjectKeys.MODULE, moduleData, null)
        val contentRoot = ContentRootData(GradleConstants.SYSTEM_ID, rootPath)
        val generatedKsp = Path.of(rootPath, "build", "generated", "ksp", "debug", "kotlin").toString()
        val unrelatedGenerated = Path.of(rootPath, "build", "generated", "source", "debug", "kotlin").toString()
        contentRoot.storePath(ExternalSystemSourceType.SOURCE_GENERATED, generatedKsp)
        contentRoot.storePath(ExternalSystemSourceType.SOURCE_GENERATED, unrelatedGenerated)
        moduleNode.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)

        val method = classOf<HikageGradleProjectResolver>().getDeclaredMethod(
            "excludeHikageGeneratedKspSources",
            classOf<DataNode<*>>()
        )
        assertTrue(method.trySetAccessible())
        method.invoke(HikageGradleProjectResolver(), moduleNode)

        val excluded = contentRoot.getPaths(ExternalSystemSourceType.EXCLUDED).map { source -> source.path }
        assertEquals(
            listOf(Path.of(generatedKsp, "com", "highcapable", "hikage", "widget").toString()),
            excluded
        )
    }

    /** Verifies a completed external-system import invalidates the model snapshot dependency. */
    fun testCompletedImportAdvancesModelModificationTracker() {
        val tracker = ExternalSystemModelModificationTracker.getInstance(project)
        val before = tracker.modificationCount

        project.messageBus.syncPublisher(ProjectDataImportListener.TOPIC)
            .onImportFinished(project.basePath)

        assertEquals(before + 1, tracker.modificationCount)
    }

    private fun storeGradleModel(model: HikageGradleModel) {
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
        moduleNode.createChild(descriptor.key, model)
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            moduleData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )
    }

    private fun model(
        isCompilerEnabled: Boolean,
        strictViewDeclarationInputFiles: List<String> = emptyList()
    ) = DefaultHikageGradleModel(
        isPluginApplied = true,
        isCompilerEnabled = isCompilerEnabled,
        viewDeclarationFiles = emptyList(),
        optionalViewDeclarationFiles = emptyList(),
        strictViewDeclarationInputFiles = strictViewDeclarationInputFiles,
        optionalViewDeclarationInputArtifacts = emptyList()
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