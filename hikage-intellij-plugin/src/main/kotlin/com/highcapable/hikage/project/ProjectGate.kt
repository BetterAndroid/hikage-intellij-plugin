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
 * This file is created by fankes on 2026/7/18.
 */
package com.highcapable.hikage.project

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Controls whether Hikage IDE features are applicable to the opened project.
 */
@Service(Service.Level.PROJECT)
class ProjectGate private constructor(private val project: Project) {

    companion object {

        private val IS_ENABLED_KEY = Key.create<CachedValue<Boolean>>("hikage.project.isEnabled")

        /**
         * Returns the Hikage project gate for [project].
         * @return [ProjectGate]
         */
        fun from(project: Project) = project.service<ProjectGate>()
    }

    /**
     * Returns whether any module in the opened project has the real `hikage-core` dependency.
     * @return [Boolean]
     */
    fun isEnabled() = if (ApplicationManager.getApplication().isReadAccessAllowed)
        cachedIsEnabled() == true
    else ApplicationManager.getApplication().runReadAction(Computable { cachedIsEnabled() == true }) == true

    /**
     * Runs [block] only when Hikage IDE features are enabled, otherwise returns [defaultValue].
     * @param defaultValue the value to return if Hikage IDE features are not enabled.
     * @param block the block of code to execute if Hikage IDE features are enabled.
     * @return [R]
     */
    fun <R> runIfEnabled(defaultValue: R, block: ProjectGate.() -> R) = if (isEnabled()) block() else defaultValue

    /**
     * Finds the preferred Android module that can receive the standard Hikage dependencies.
     * @return the target module, or `null` when the project has no synchronized Android module.
     */
    fun findDependencyTarget() = if (ApplicationManager.getApplication().isReadAccessAllowed)
        project.findAndroidModule()
    else ApplicationManager.getApplication().runReadAction(Computable { project.findAndroidModule() })

    /**
     * Adds the BOM-managed standard Hikage dependencies and Gradle plugin to [module].
     * @param module the target Android module.
     * @return [Boolean] whether the project model was successfully updated.
     */
    fun addHikageDependencies(module: Module): Boolean {
        val dependencyService = GradleDependencyService.getInstance(project)
        val shouldAddBetterAndroidExtension = dependencyService.requiresDependency(
            module,
            Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE,
            Coordinates.EXTENSION_BETTERANDROID_MODULE
        )
        val dependencies = buildList {
            addAll(Coordinates.STANDARD_DEPENDENCY_MODULES)
            if (shouldAddBetterAndroidExtension) add(Coordinates.EXTENSION_BETTERANDROID_MODULE)
        }
        val isAdded = dependencyService.addDependenciesAndPlugin(
            module = module,
            platformCoordinate = Coordinates.BOM_DEPENDENCY,
            coordinates = dependencies,
            pluginId = Coordinates.GRADLE_PLUGIN_ID,
            pluginVersion = Coordinates.GRADLE_PLUGIN_VERSION,
            pluginClasspathCoordinate = Coordinates.GRADLE_PLUGIN_MODULE,
            pluginAlias = Coordinates.GRADLE_PLUGIN_ALIAS,
            pluginVersionAlias = Coordinates.GRADLE_PLUGIN_VERSION_ALIAS
        )
        if (isAdded) project.putUserData(IS_ENABLED_KEY, null)
        return isAdded
    }

    private fun cachedIsEnabled() = CachedValuesManager.getManager(project).getCachedValue(
        project,
        IS_ENABLED_KEY,
        {
            CachedValueProvider.Result.create(hasHikageCoreDependency(), ProjectRootModificationTracker.getInstance(project))
        },
        false
    )

    private fun hasHikageCoreDependency() = ModuleManager.getInstance(project).modules.any { module ->
        ModuleRootManager.getInstance(module).orderEntries
            .asSequence()
            .filterIsInstance<LibraryOrderEntry>()
            .mapNotNull(LibraryOrderEntry::getLibrary)
            .mapNotNull(JavaLibraryUtil::getMavenCoordinates)
            .any { coordinates ->
                coordinates.groupId == Coordinates.GROUP && coordinates.artifactId == Coordinates.CORE_ARTIFACT
            }
    }

    private fun Project.findAndroidModule() = ModuleManager.getInstance(this).modules
        .asSequence()
        .mapNotNull { module -> GradleAndroidModel.get(module)?.let { model -> module to model.androidProject.projectType } }
        .sortedWith(compareBy<Pair<Module, IdeAndroidProjectType>> { (_, type) ->
            type != IdeAndroidProjectType.PROJECT_TYPE_APP
        }.thenBy { (module) -> module.name })
        .map(Pair<Module, IdeAndroidProjectType>::first)
        .firstOrNull()
}