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
package com.highcapable.hikage.intellij.project.model.gradle.resolver

import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.intellij.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension

/**
 * Requests and attaches [HikageGradleModel] during each Gradle project sync.
 */
class HikageGradleProjectResolver : AbstractProjectResolverExtension() {

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
            }

        super.populateModuleExtraModels(gradleModule, moduleDataNode)
    }
}