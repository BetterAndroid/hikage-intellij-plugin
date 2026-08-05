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
package com.highcapable.hikage.project.model.gradle

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlin.reflect.KClass

/**
 * Reads custom Gradle tooling models from the latest synchronized project structure.
 */
object GradleToolingModels {

    /**
     * Describes an item of a Gradle tooling model that can be read from the synchronized project structure.
     */
    data class Descriptor<T : Any>(
        val type: KClass<T>,
        val key: Key<T>
    )

    /** Returns [descriptor]'s value for [module], or null when Gradle has not synchronized it. */
    fun <T : Any> find(module: Module, descriptor: Descriptor<T>): T? {
        val project = module.project
        val roots = project.gradleRoots().toList()
        val externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module)
        if (externalProjectId != null) {
            roots.asSequence()
                .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, ProjectKeys.MODULE).asSequence() }
                .filter { node -> node.data.id == externalProjectId }
                .firstNotNullOfOrNull { node -> ExternalSystemApiUtil.find(node, descriptor.key)?.data }
                ?.let { model -> return model }
            roots.asSequence()
                .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, GradleSourceSetData.KEY).asSequence() }
                .firstOrNull { node -> node.data.id == externalProjectId }
                ?.let { node -> ExternalSystemApiUtil.findParent(node, ProjectKeys.MODULE) }
                ?.let { node -> ExternalSystemApiUtil.find(node, descriptor.key)?.data }
                ?.let { model -> return model }
        }

        val externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module)
        return roots.asSequence()
            .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, ProjectKeys.MODULE).asSequence() }
            .filter { node -> node.data.linkedExternalProjectPath == externalProjectPath }
            .firstNotNullOfOrNull { node -> ExternalSystemApiUtil.find(node, descriptor.key)?.data }
    }

    /** Returns every synchronized [descriptor] instance. */
    fun <T : Any> all(project: Project, descriptor: Descriptor<T>) = project.gradleRoots()
        .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, ProjectKeys.MODULE).asSequence() }
        .mapNotNull { moduleNode ->
            ExternalSystemApiUtil.find(moduleNode, descriptor.key)?.data
        }

    private fun Project.gradleRoots() = ExternalProjectsDataStorage.getInstance(this)
        .list(GradleConstants.SYSTEM_ID)
        .asSequence()
        .map { info -> info.externalProjectStructure }
}