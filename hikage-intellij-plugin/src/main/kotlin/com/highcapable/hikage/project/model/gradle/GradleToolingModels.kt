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
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Reads custom Gradle tooling models from the latest synchronized project structure.
 */
object GradleToolingModels {

    private class ModelCache {
        val graphs = ConcurrentHashMap<Descriptor<*>, ModelGraph<*>>()
    }

    private data class ModelGraph<T : Any>(
        val moduleModelsById: Map<String, T>,
        val sourceSetModelsById: Map<String, T?>,
        val moduleModelsByPath: Map<String, T>,
        val models: List<T>
    )

    /**
     * Describes an item of a Gradle tooling model that can be read from the synchronized project structure.
     */
    data class Descriptor<T : Any>(
        val type: KClass<T>,
        val key: Key<T>
    )

    /** Returns [descriptor]'s value for [module], or null when Gradle has not synchronized it. */
    fun <T : Any> find(module: Module, descriptor: Descriptor<T>): T? {
        val graph = module.project.modelGraph(descriptor)
        val externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module)
        if (externalProjectId != null) {
            graph.moduleModelsById[externalProjectId]?.let { model -> return model }
            return graph.sourceSetModelsById[externalProjectId]
        }

        val externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module)
        return graph.moduleModelsByPath[externalProjectPath]
    }

    /** Returns every synchronized [descriptor] instance. */
    fun <T : Any> all(project: Project, descriptor: Descriptor<T>) = project.modelGraph(descriptor).models

    private fun <T : Any> Project.modelGraph(descriptor: Descriptor<T>): ModelGraph<T> {
        val cache = CachedValuesManager.getManager(this).getCachedValue(this) {
            CachedValueProvider.Result.create(
                ModelCache(),
                ExternalProjectsDataStorage.getInstance(this),
                ProjectRootModificationTracker.getInstance(this)
            )
        }

        @Suppress("UNCHECKED_CAST")
        return cache.graphs.computeIfAbsent(descriptor) { buildModelGraph(descriptor) } as ModelGraph<T>
    }

    private fun <T : Any> Project.buildModelGraph(descriptor: Descriptor<T>): ModelGraph<T> {
        val roots = gradleRoots().toList()
        val moduleModelsById = linkedMapOf<String, T>()
        val sourceSetModelsById = linkedMapOf<String, T?>()
        val moduleModelsByPath = linkedMapOf<String, T>()
        val models = roots.asSequence()
            .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, ProjectKeys.MODULE).asSequence() }
            .mapNotNull { moduleNode ->
                val model = ExternalSystemApiUtil.find(moduleNode, descriptor.key)?.data ?: return@mapNotNull null
                moduleModelsById.putIfAbsent(moduleNode.data.id, model)
                moduleModelsByPath.putIfAbsent(moduleNode.data.linkedExternalProjectPath, model)
                model
            }
            .toList()

        roots.asSequence()
            .flatMap { root -> ExternalSystemApiUtil.findAllRecursively(root, GradleSourceSetData.KEY).asSequence() }
            .forEach { sourceSetNode ->
                val sourceSetId = sourceSetNode.data.id
                if (sourceSetModelsById.containsKey(sourceSetId)) return@forEach
                val model = ExternalSystemApiUtil.findParent(sourceSetNode, ProjectKeys.MODULE)
                    ?.let { moduleNode -> ExternalSystemApiUtil.find(moduleNode, descriptor.key)?.data }
                sourceSetModelsById[sourceSetId] = model
            }

        return ModelGraph(
            moduleModelsById = moduleModelsById,
            sourceSetModelsById = sourceSetModelsById,
            moduleModelsByPath = moduleModelsByPath,
            models = models
        )
    }

    private fun Project.gradleRoots() = ExternalProjectsDataStorage.getInstance(this)
        .list(GradleConstants.SYSTEM_ID)
        .asSequence()
        .map { info -> info.externalProjectStructure }
}