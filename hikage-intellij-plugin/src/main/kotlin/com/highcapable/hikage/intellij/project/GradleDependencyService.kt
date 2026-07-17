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
 * This file is created by fankes on 2026/7/17.
 */
package com.highcapable.hikage.intellij.project

import com.android.ide.common.gradle.Dependency
import com.android.tools.idea.gradle.dependencies.CatalogDependenciesInserter
import com.android.tools.idea.gradle.dependencies.DependenciesHelper
import com.android.tools.idea.gradle.dependencies.GradleDependencyManager
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager
import com.android.tools.idea.projectsystem.getSyncManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * Provides source-aware Gradle dependency detection and modification for the current project.
 */
@Service(Service.Level.PROJECT)
class GradleDependencyService(private val project: Project) {

    companion object {

        private const val DEFAULT_CONFIGURATION = "implementation"

        /** Returns the Gradle dependency service for [project]. */
        fun getInstance(project: Project) = project.service<GradleDependencyService>()
    }

    /**
     * Returns whether [coordinate] is directly declared for [module] or supplies [capabilityClassName].
     * @param module the target module to check.
     * @param coordinate the Gradle dependency coordinate to check.
     * @param capabilityClassName the fully qualified class name to check for existence in [module].
     * @return [Boolean]
     */
    fun isDependencyApplied(module: Module, coordinate: String, capabilityClassName: String? = null) = runCatching {
        val target = Dependency.parse(coordinate)
        if (target.group == null) return@runCatching true
        val buildModel = ProjectBuildModel.get(project).getModuleBuildModel(module) ?: return@runCatching true
        val isDeclared = buildModel.hasDependency(target)
        if (isDeclared || capabilityClassName == null) return@runCatching isDeclared

        JavaPsiFacade.getInstance(project).findClass(
            capabilityClassName,
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ) != null
    }.getOrDefault(true)

    /**
     * Adds [coordinate] to [module] and requests Gradle sync after a successful modification.
     * @param module the target module to add the dependency to.
     * @param coordinate the Gradle dependency coordinate to add.
     * @param configuration the Gradle dependency configuration to add to, default is `implementation`.
     * @param platformCoordinate an optional platform coordinate that should manage the dependency version.
     * @return [Boolean] whether the dependency was successfully added.
     */
    fun addDependency(
        module: Module,
        coordinate: String,
        configuration: String = DEFAULT_CONFIGURATION,
        platformCoordinate: String? = null
    ): Boolean {
        val dependency = Dependency.parse(coordinate)
        if (dependency.group == null) return false

        val projectModel = ProjectBuildModel.get(project)
        val buildModel = projectModel.getModuleBuildModel(module) ?: return false
        val platform = platformCoordinate?.let(Dependency::parse)
        val isAdded = if (platform?.group != null && buildModel.hasDependency(platform))
            addPlatformManagedDependency(projectModel, buildModel, dependency, coordinate, configuration)
        else GradleDependencyManager.getInstance(project)
            .addDependencies(module, listOf(dependency), configuration)
        if (isAdded) project.getSyncManager().requestSyncProject(ProjectSystemSyncManager.SyncReason.PROJECT_DEPENDENCY_UPDATED)
        return isAdded
    }

    /**
     * Android Studio resolves a versionless coordinate before its normal insertion policy runs. That would detach a
     * library from an already applied BOM, so this path writes the versionless declaration through the Gradle DSL model.
     * @param projectModel the Gradle project model.
     * @param buildModel the Gradle build model for the target module.
     * @param dependency the Gradle dependency to add.
     * @param coordinate the Gradle dependency coordinate to add.
     * @param configuration the Gradle dependency configuration to add to.
     * @return [Boolean] whether the dependency was successfully added.
     */
    private fun addPlatformManagedDependency(
        projectModel: ProjectBuildModel,
        buildModel: GradleBuildModel,
        dependency: Dependency,
        coordinate: String,
        configuration: String
    ) = WriteCommandAction.writeCommandAction(project).withName("Add Dependency").compute<Boolean, RuntimeException> {
        if (buildModel.hasDependency(dependency)) return@compute false

        val dependencies = buildModel.dependencies()
        val catalog = DependenciesHelper.getDefaultCatalogModel(projectModel)
        if (catalog == null)
            dependencies.addArtifact(configuration, coordinate)
        else {
            val alias = catalog.libraryDeclarations().getAll().entries.firstOrNull { (_, declaration) ->
                val spec = declaration.getSpec()
                spec.getGroup() == dependency.group && spec.getName() == dependency.name &&
                    spec.getVersion()?.compactNotation().isNullOrBlank()
            }?.key ?: CatalogDependenciesInserter.addCatalogLibrary(catalog, coordinate) ?: return@compute false
            val declaration = catalog.libraries().findProperty(alias)
            dependencies.addArtifact(configuration, ReferenceTo(declaration, dependencies))
        }

        projectModel.applyChanges()
        true
    }

    private fun GradleBuildModel.hasDependency(target: Dependency): Boolean {
        val group = target.group ?: return false
        return dependencies().artifacts().any { dependency ->
            dependency.spec.group == group && dependency.spec.name == target.name
        }
    }
}