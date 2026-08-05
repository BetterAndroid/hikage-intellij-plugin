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
 * This file is created by fankes on 2026/7/20.
 */
package com.highcapable.hikage.project

import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Controls whether runtime-backed Hikage attribute IDE features are available for a source module.
 */
object HikageRuntimeAttributeGate {

    private val IS_ENABLED_KEY = Key.create<CachedValue<Boolean>>("hikage.runtime-attribute.isEnabled")

    /**
     * Returns whether the module containing [element] applies the Hikage runtime attribute capability.
     * @param element the source element whose owning module should be checked.
     * @return [Boolean]
     */
    fun isEnabled(element: PsiElement): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        return isEnabled(module)
    }

    /**
     * Returns whether [module] applies the Hikage runtime attribute capability.
     * @param module the source module to check.
     * @return [Boolean]
     */
    fun isEnabled(module: Module): Boolean {
        val project = module.project

        return CachedValuesManager.getManager(project).getCachedValue(
            module,
            IS_ENABLED_KEY,
            {
                CachedValueProvider.Result.create(
                    GradleDependencyService.getInstance(project).isDependencyApplied(
                        module,
                        Coordinates.RUNTIME_ATTRIBUTE_MODULE,
                        HikageSymbols.HIKAGE_RUNTIME_ATTRIBUTE_RESOLVER
                    ),
                    ProjectRootModificationTracker.getInstance(project)
                )
            },
            false
        )
    }

    /**
     * Returns whether the synchronized project contains the runtime-attribute dependency.
     * @param project the project whose settings availability should be checked.
     * @return [Boolean]
     */
    fun isEnabled(project: Project): Boolean = if (ApplicationManager.getApplication().isReadAccessAllowed)
        project.hasRuntimeAttributeDependency()
    else ApplicationManager.getApplication().runReadAction(Computable { project.hasRuntimeAttributeDependency() })

    /**
     * Finds the first synchronized Hikage module that can receive the runtime-attribute dependency.
     * @param project the project containing the target module.
     * @return the target module, or `null` when no Hikage module is available.
     */
    fun findDependencyTarget(project: Project) = if (ApplicationManager.getApplication().isReadAccessAllowed)
        project.findHikageModule()
    else ApplicationManager.getApplication().runReadAction(Computable { project.findHikageModule() })

    /**
     * Adds the BOM-managed Hikage runtime-attribute dependency to [module].
     * @param module the target Hikage module.
     * @return [Boolean] whether the dependency was successfully added.
     */
    fun addRuntimeAttributeDependency(module: Module): Boolean {
        val isAdded = GradleDependencyService.getInstance(module.project).addDependency(
            module,
            Coordinates.RUNTIME_ATTRIBUTE_MODULE,
            platformCoordinate = Coordinates.BOM_DEPENDENCY
        )
        if (isAdded) invalidate(module)
        return isAdded
    }

    private fun invalidate(module: Module) = module.putUserData(IS_ENABLED_KEY, null)

    private fun Project.hasRuntimeAttributeDependency() = ModuleManager.getInstance(this).modules.any { module ->
        module.hasMavenDependency(Coordinates.RUNTIME_ATTRIBUTE_ARTIFACT)
    }

    private fun Project.findHikageModule() = ModuleManager.getInstance(this).modules
        .sortedBy(Module::getName)
        .firstOrNull { module -> module.hasMavenDependency(Coordinates.CORE_ARTIFACT) }

    private fun Module.hasMavenDependency(artifact: String) = ModuleRootManager.getInstance(this).orderEntries
        .asSequence()
        .filterIsInstance<LibraryOrderEntry>()
        .mapNotNull(LibraryOrderEntry::getLibrary)
        .mapNotNull(JavaLibraryUtil::getMavenCoordinates)
        .any { coordinates -> coordinates.groupId == Coordinates.GROUP && coordinates.artifactId == artifact }
}