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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.project

import com.highcapable.hikage.intellij.model.HikageCoordinates
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Provides project-level Hikage capability checks.
 */
@Service(Service.Level.PROJECT)
class HikageProjectService(private val project: Project) {

    companion object {

        private val HIKAGE_PROJECT_KEY = Key.create<CachedValue<Boolean>>("hikage.isProject")

        /**
         * Returns the Hikage project service for [project].
         */
        fun getInstance(project: Project) = project.service<HikageProjectService>()
    }

    /**
     * Returns whether the opened project depends on `hikage-core`.
     * @return [Boolean]
     */
    fun isHikageProject(): Boolean = CachedValuesManager.getManager(project).getCachedValue(
        project,
        HIKAGE_PROJECT_KEY,
        {
            CachedValueProvider.Result.create(hasHikageCoreDependency(), ProjectRootModificationTracker.getInstance(project))
        },
        false
    )

    private fun hasHikageCoreDependency() = ModuleManager.getInstance(project).modules.any { module ->
        ModuleRootManager.getInstance(module).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .any { entry -> entry.libraryName?.contains(HikageCoordinates.CORE_MODULE) == true }
    }
}