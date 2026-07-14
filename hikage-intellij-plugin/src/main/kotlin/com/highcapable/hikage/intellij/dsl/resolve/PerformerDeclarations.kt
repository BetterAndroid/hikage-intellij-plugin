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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Provides project-level Hikage performer declarations.
 */
object PerformerDeclarations {

    private val CACHE_KEY = Key.create<Snapshot>("hikage.performer.resolve.declarations")
    private val cacheLock = Any()

    private data class Dependencies(
        val projectRoots: Long,
        val psi: Long,
        val vfs: Long,
        val externalSystemModel: Long
    )

    private data class Snapshot(
        val dependencies: Dependencies,
        val declarations: List<PerformerDeclaration>
    )

    /**
     * Returns the cached list of [PerformerDeclaration] for the given [project].
     * @param project the [Project] to resolve declarations for.
     * @return [List]<[PerformerDeclaration]>
     */
    fun resolve(project: Project): List<PerformerDeclaration> {
        val dependencies = project.currentDependencies()
        project.getUserData(CACHE_KEY)?.takeIf { snapshot -> snapshot.dependencies == dependencies }
            ?.let(Snapshot::declarations)
            ?.let { declarations -> return declarations }

        return synchronized(cacheLock) {
            val currentDependencies = project.currentDependencies()
            project.getUserData(CACHE_KEY)?.takeIf { snapshot -> snapshot.dependencies == currentDependencies }
                ?.declarations
                ?: ApplicationManager.getApplication().runReadAction(Computable {
                    // CachedValue verifies every recomputation for idempotence. Declaration output
                    // directories can be replaced atomically by Gradle, while their VFS view is
                    // being refreshed. Keep a tracker-validated snapshot to preserve the same
                    // invalidation contract without turning that harmless transition into an IDE error.
                    val declarations = PerformerDeclarationCollector(project).collect()
                    project.putUserData(CACHE_KEY, Snapshot(currentDependencies, declarations))
                    declarations
                })
        }
    }

    private fun Project.currentDependencies() = Dependencies(
        projectRoots = ProjectRootModificationTracker.getInstance(this).modificationCount,
        psi = PsiModificationTracker.getInstance(this).modificationCount,
        vfs = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount,
        externalSystemModel = ExternalSystemModelModificationTracker.getInstance(this).modificationCount
    )
}