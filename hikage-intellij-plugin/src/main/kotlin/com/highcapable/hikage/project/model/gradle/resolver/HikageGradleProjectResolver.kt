/*
 * Hikage - A real-time Android View runtime powered by Kotlin DSL.
 * Copyright (C) 2019 HighCapable
 * https://github.com/BetterAndroid/Hikage
 *
 * Apache License Version 2.0 (the "License");
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
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.project.model.gradle.resolver

import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import java.nio.file.Path

/**
 * Requests and attaches [HikageGradleModel] during each Gradle project sync.
 */
class HikageGradleProjectResolver : AbstractProjectResolverExtension() {

    private companion object {

        const val GENERATED_DIRECTORY_NAME = "generated"
        const val KSP_DIRECTORY_NAME = "ksp"
        const val KOTLIN_DIRECTORY_NAME = "kotlin"

        val hikageWidgetPath: Path = HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX.split(".").let { segments ->
            Path.of(segments.first(), *segments.drop(1).toTypedArray())
        }
    }

    override fun getExtraProjectModelClasses() = setOf(classOf<HikageGradleModel>())

    override fun populateModuleExtraModels(gradleModule: IdeaModule, moduleDataNode: DataNode<ModuleData>) {
        resolverCtx.getExtraProject(gradleModule, classOf<HikageGradleModel>())
            ?.takeIf(HikageGradleModel::isPluginApplied)
            ?.let { model ->
                val data = DefaultHikageGradleModel(
                    isPluginApplied = model.isPluginApplied,
                    isCompilerEnabled = model.isCompilerEnabled,
                    viewDeclarationFiles = model.viewDeclarationFiles,
                    optionalViewDeclarationFiles = model.optionalViewDeclarationFiles,
                    strictViewDeclarationInputFiles = model.strictViewDeclarationInputFiles,
                    optionalViewDeclarationInputArtifacts = model.optionalViewDeclarationInputArtifacts
                )
                moduleDataNode.createChild(HikageGradleToolingModel.key, data)
                excludeHikageGeneratedKspSources(moduleDataNode)
            }

        super.populateModuleExtraModels(gradleModule, moduleDataNode)
    }

    private fun excludeHikageGeneratedKspSources(moduleDataNode: DataNode<ModuleData>) {
        ExternalSystemApiUtil.findAllRecursively(moduleDataNode, ProjectKeys.CONTENT_ROOT)
            .map { node -> node.data }
            .forEach { contentRoot ->
                ExternalSystemSourceType.entries.asSequence()
                    .filterNot(ExternalSystemSourceType::isExcluded)
                    .flatMap { sourceType -> contentRoot.getPaths(sourceType).asSequence() }
                    .map(ContentRootData.SourceRoot::getPath)
                    .filter { sourcePath -> sourcePath.isGeneratedKspKotlinDirectory() }
                    .map { sourcePath -> Path.of(sourcePath).resolve(hikageWidgetPath).toString() }
                    .forEach { excludedPath -> contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, excludedPath) }
            }
    }

    private fun String.isGeneratedKspKotlinDirectory() = Path.of(this).normalize().let { sourcePath ->
        sourcePath.fileName?.toString() == KOTLIN_DIRECTORY_NAME &&
            sourcePath.parent?.parent?.fileName?.toString() == KSP_DIRECTORY_NAME &&
            sourcePath.parent?.parent?.parent?.fileName?.toString() == GENERATED_DIRECTORY_NAME
    }
}