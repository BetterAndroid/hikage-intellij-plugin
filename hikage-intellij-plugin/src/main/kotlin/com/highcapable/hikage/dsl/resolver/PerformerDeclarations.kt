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
package com.highcapable.hikage.dsl.resolver

import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.project.model.gradle.tracker.ExternalSystemModelModificationTracker
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.createProjectWideSourceModificationTracker

/**
 * Provides project-level Hikage performer declarations.
 */
@OptIn(KaPlatformInterface::class)
object PerformerDeclarations {

    private const val JSON_LANGUAGE_ID = "JSON"

    private val CACHE_KEY = Key.create<Snapshot>("hikage.performer.resolve.declarations")
    private val cacheLock = Any()

    private data class Dependencies(
        val projectRoots: Long,
        val kotlinSource: Long,
        val declarationInputPsi: Long,
        val vfs: Long,
        val externalSystemModel: Long
    )

    private data class Snapshot(
        val dependencies: Dependencies,
        val declarations: List<PerformerDeclaration>,
        val duplicateViewClasses: Set<String>
    )

    /**
     * Returns the cached list of [PerformerDeclaration] for the given [project].
     * @param project the [Project] to resolve declarations for.
     * @return [List]<[PerformerDeclaration]>
     */
    fun resolve(project: Project) = project.resolveSnapshot().declarations

    /**
     * Returns the View classes that have more than one active project declaration source.
     * @param project the [Project] to resolve duplicate view classes for.
     * @return [Set]<[String]>
     */
    fun duplicateViewClasses(project: Project) = project.resolveSnapshot().duplicateViewClasses

    private fun Project.resolveSnapshot(): Snapshot {
        val targetProject = this
        val dependencies = targetProject.currentDependencies()
        getUserData(CACHE_KEY)?.takeIf { snapshot -> snapshot.dependencies == dependencies }
            ?.let { snapshot -> return snapshot }

        return synchronized(cacheLock) {
            val currentDependencies = currentDependencies()
            getUserData(CACHE_KEY)?.takeIf { snapshot -> snapshot.dependencies == currentDependencies }
                ?: ApplicationManager.getApplication().runReadAction(Computable {
                    // CachedValue verifies every recomputation for idempotence. Declaration output
                    // directories can be replaced atomically by Gradle, while their VFS view is
                    // being refreshed. Keep a tracker-validated snapshot to preserve the same
                    // invalidation contract without turning that harmless transition into an IDE error.
                    val result = PerformerDeclarationCollector.from(targetProject).collectResult()
                    Snapshot(currentDependencies, result.declarations, result.duplicateViewClasses).also { snapshot ->
                        targetProject.putUserData(CACHE_KEY, snapshot)
                    }
                })
        }
    }

    private fun Project.currentDependencies() = Dependencies(
        projectRoots = ProjectRootModificationTracker.getInstance(this).modificationCount,
        kotlinSource = createProjectWideSourceModificationTracker().modificationCount,
        declarationInputPsi = PsiModificationTracker.getInstance(this).forLanguages { language ->
            language == JavaLanguage.INSTANCE || language.id == JSON_LANGUAGE_ID
        }.modificationCount,
        vfs = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount,
        externalSystemModel = ExternalSystemModelModificationTracker.getInstance(this).modificationCount
    )
}