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
package com.highcapable.hikage.dsl

import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Verifies K2 resolve-extension creation against project and owning-module gates.
 */
@OptIn(KaExperimentalApi::class)
class PerformerResolveExtensionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies that only a source module with its own enabled Hikage model receives the extension. */
    fun testResolveExtensionRequiresEnabledOwningModuleModel() {
        val file = configureKotlinByText(
            "ResolveExtensionGate.kt",
            """
            package sample

            class ResolveExtensionGate
            """.trimIndent()
        )
        val sourceModule = KaModuleProvider.getModule(project, file, null)
        val provider = PerformerResolveExtensionProvider()

        assertEmpty(provider.provideExtensionsFor(sourceModule))

        enableHikageProject()
        storeGradleModels(siblingModel = model())
        assertEmpty(provider.provideExtensionsFor(sourceModule))

        storeGradleModels(owningModel = model(), siblingModel = model())
        val extensions = provider.provideExtensionsFor(sourceModule)

        assertEquals(1, extensions.size)
    }

    private fun storeGradleModels(
        owningModel: HikageGradleModel? = null,
        siblingModel: HikageGradleModel? = null
    ) {
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
        owningModel?.let { moduleNode.createChild(HikageGradleToolingModel.key, it) }
        siblingModel?.let { model ->
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
            ).createChild(HikageGradleToolingModel.key, model)
        }
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            moduleData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )
    }

    private fun model() = DefaultHikageGradleModel(
        isPluginApplied = true,
        isCompilerEnabled = true,
        viewDeclarationFiles = emptyList(),
        optionalViewDeclarationFiles = emptyList(),
        strictViewDeclarationInputFiles = emptyList(),
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