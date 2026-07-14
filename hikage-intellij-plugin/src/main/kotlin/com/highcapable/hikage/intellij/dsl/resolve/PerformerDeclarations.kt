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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.intellij.dsl.resolve

import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.project.model.gradle.tracker.ExternalSystemModelModificationTracker
import com.highcapable.hikage.intellij.project.model.gradle.tracker.GeneratedKspSourcesModificationTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Provides project-level Hikage performer declarations.
 */
object PerformerDeclarations {

    private val CACHE_KEY = Key.create<CachedValue<List<PerformerDeclaration>>>("hikage.performer.resolve.declarations")

    /**
     * Returns the cached list of [PerformerDeclaration] for the given [project].
     * @param project the [Project] to resolve declarations for.
     * @return [List]<[PerformerDeclaration]>
     */
    fun resolve(project: Project) = CachedValuesManager.getManager(project).getCachedValue(
        project, CACHE_KEY, {
            val declarations = ApplicationManager.getApplication().runReadAction(Computable {
                PerformerDeclarationCollector(project).collect()
            })
            CachedValueProvider.Result.create(
                declarations,
                ProjectRootModificationTracker.getInstance(project),
                // The collector validates constructor parameter text for source @HikageView
                // declarations. That text can change without a Java-structure event, so the
                // dynamic K2 stubs must follow ordinary PSI edits instead of sticking to a stale
                // invalid declaration set.
                PsiModificationTracker.MODIFICATION_COUNT,
                // KSP creates and removes its output directory without changing annotated source PSI.
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                // K2 caches resolve extensions separately from ordinary VFS changes. Keep the
                // dynamic performer files aligned when KSP source roots appear or disappear.
                GeneratedKspSourcesModificationTracker.getInstance(project),
                // Gradle sync replaces custom tooling-model data without necessarily changing project roots.
                ExternalSystemModelModificationTracker.getInstance(project)
            )
        }, false
    ) ?: emptyList()
}