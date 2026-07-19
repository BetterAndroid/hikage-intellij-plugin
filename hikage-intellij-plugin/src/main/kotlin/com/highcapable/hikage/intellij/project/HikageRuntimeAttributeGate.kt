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
package com.highcapable.hikage.intellij.project

import com.highcapable.hikage.intellij.model.Coordinates
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
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
        val project = element.project

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
}