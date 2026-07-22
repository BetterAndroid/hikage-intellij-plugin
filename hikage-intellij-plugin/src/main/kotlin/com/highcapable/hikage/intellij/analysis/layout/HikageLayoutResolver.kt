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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.intellij.analysis.layout

import com.highcapable.hikage.intellij.analysis.layout.helper.HikageLayoutIdHelper
import com.highcapable.hikage.intellij.analysis.layout.helper.HikageLayoutSourceHelper
import com.highcapable.hikage.intellij.analysis.layout.helper.HikageLayoutTypeHelper
import com.highcapable.hikage.intellij.analysis.layout.model.HikageLayout
import com.highcapable.hikage.intellij.analysis.layout.model.HikageLayout.Id
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiClass
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeReference
import java.util.concurrent.CancellationException

/**
 * Resolves one Hikage receiver into the shared layout-ID model consumed by editor features.
 */
class HikageLayoutResolver private constructor(project: Project) {

    companion object {

        /**
         * Creates a layout-ID resolver for [project].
         * @param project the current IDE project.
         * @return [HikageLayoutResolver]
         */
        fun from(project: Project) = HikageLayoutResolver(project)
    }

    private val typeHelper = HikageLayoutTypeHelper(project)
    private val sourceHelper = HikageLayoutSourceHelper(typeHelper)
    private val idHelper = HikageLayoutIdHelper(sourceHelper, typeHelper)
    private val rootTracker = ProjectRootModificationTracker.getInstance(project)
    private val dumbTracker = DumbService.getInstance(project).modificationTracker

    /** Returns whether [receiver] is a real Hikage runtime value. */
    fun isHikage(receiver: KtExpression) = failOpen { typeHelper.isHikage(receiver) } == true

    /** Resolves the class represented by [typeReference] without exposing the helper layer. */
    fun resolveTypeClass(typeReference: KtTypeReference) = failOpen { typeHelper.resolveTypeClass(typeReference) }

    /** Creates a Kotlin source reference for [viewClass] in [file]. */
    fun createTypeReference(file: KtFile, viewClass: PsiClass) = typeHelper.createTypeReference(file, viewClass)

    /** Resolves a statically known layout ID passed through [expression]. */
    fun resolveIdValue(expression: KtExpression) = failOpen { idHelper.resolveIdValue(expression) }

    /**
     * Finds the direct Hikage or Delegate performer scope lexically containing [expression].
     *
     * Each resolved scope owns its IDs independently; IDs from separate layouts must not be merged.
     */
    fun findDeclarationScope(expression: KtExpression) = failOpen { sourceHelper.findContainingSource(expression)?.anchor }

    /** Resolves [receiver] when it is a real Hikage instance with statically traceable source. */
    fun resolve(receiver: KtExpression): HikageLayout? {
        if (!isHikage(receiver)) return null
        val sources = failOpen { sourceHelper.resolve(receiver) }.orEmpty()
        if (sources.isEmpty()) return null

        val models = sources.map { source ->
            CachedValuesManager.getCachedValue(source.anchor) {
                CachedValueProvider.Result.create(
                    idHelper.resolve(source),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    rootTracker,
                    dumbTracker
                )
            }
        }
        return merge(models)
    }

    private fun merge(models: List<HikageLayout>): HikageLayout {
        val alwaysPresentIds = models
            .map { model ->
                model.ids.filter(Id::isAlwaysPresent).map(Id::name).toSet()
            }
            .reduceOrNull { commonIds, ids -> commonIds intersect ids }
            .orEmpty()
        val ids = models.flatMap(HikageLayout::ids)
            .groupBy(Id::name)
            .map { (name, declarations) ->
                val classes = declarations.map(Id::viewClass)
                    .distinctBy { viewClass -> viewClass?.qualifiedName ?: viewClass }
                declarations.first().copy(
                    viewClass = classes.singleOrNull(),
                    isAlwaysPresent = name in alwaysPresentIds
                )
            }
        val roots = models.mapNotNull(HikageLayout::root)
        val rootClasses = roots.distinctBy { root -> root.viewClass.qualifiedName ?: root.viewClass }
        val root = roots.firstOrNull()?.takeIf { roots.size == models.size && rootClasses.size == 1 }

        return HikageLayout(ids, root)
    }

    private inline fun <T> failOpen(action: () -> T): T? = try {
        action()
    } catch (error: Exception) {
        if (error is ControlFlowException || error is CancellationException) throw error
        null
    }
}