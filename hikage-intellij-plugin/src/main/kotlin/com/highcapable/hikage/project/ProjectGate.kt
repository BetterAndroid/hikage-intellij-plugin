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

import com.highcapable.hikage.model.Coordinates
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
}