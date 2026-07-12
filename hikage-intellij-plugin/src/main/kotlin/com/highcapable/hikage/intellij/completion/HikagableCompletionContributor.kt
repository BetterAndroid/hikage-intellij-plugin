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
package com.highcapable.hikage.intellij.completion

import com.highcapable.hikage.intellij.completion.decorator.DefaultLayoutParamsLookupDecorator
import com.highcapable.hikage.intellij.inspection.DeclarationMatcher
import com.highcapable.hikage.intellij.model.Symbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.highcapable.hikage.intellij.settings.service.SettingsService
import com.highcapable.hikage.intellij.utils.K2LookupObject
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.impl.TopPriorityLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.codeInsight.lookup.WeighingContext
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaImplicitReceiver
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtElement

/**
 * Boosts `Hikagable` function completion items above same-named classes inside Hikage performer scopes.
 */
class HikagableCompletionContributor : CompletionContributor() {

    private companion object {
        const val HIKAGABLE_PRIORITY = 1_000_000.0
        const val HIKAGABLE_GROUPING = -1_000_000
        const val HIKAGABLE_EXPLICIT_PROXIMITY = -1_000_000
        const val HIKAGE_PERFORMER_FUNCTION_WEIGHT = 0
        const val OTHER_LOOKUP_WEIGHT = 1
        const val PRIORITY_WEIGHER_ID = "priority"
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC || !parameters.isInHikagePerformerScope()) {
            super.fillCompletionVariants(parameters, result)
            return
        }

        val hikageWeigher = HikagePerformerFunctionWeigher()
        val hikageSorter = CompletionSorter
            .defaultSorter(parameters, result.prefixMatcher)
            .weighBefore(PRIORITY_WEIGHER_ID, hikageWeigher)
        val hikageResult = result.withRelevanceSorter(hikageSorter)
        val shouldFillDefaultLayoutParams = SettingsService
            .of(parameters.position.project)
            .isDefaultLayoutParamsAutoCompletionEnabled
        val lookupElements = result
            .runRemainingContributors(parameters, false)
            .map { completionResult -> completionResult.lookupElement.withHikagePriority(shouldFillDefaultLayoutParams) }

        // Kotlin completion may stream classifier candidates before extension function candidates.
        // Passing items one by one lets the lookup arrange and preselect the early class row before
        // the Hikage performer function exists in the list, so collect the remaining contributors
        // first and add them as one sorted batch.
        hikageResult.addAllElements(lookupElements)
        result.stopHere()
    }

    private fun CompletionParameters.isInHikagePerformerScope(): Boolean {
        val position = position
        if (position.language != KotlinLanguage.INSTANCE) return false
        if (!ProjectService.getInstance(position.project).isHikageProject()) return false
        val ktPosition = PsiTreeUtil.getParentOfType(position, classOf<KtElement>(), false) ?: return false

        return ktPosition.isInHikagePerformerScope()
    }

    private fun LookupElement.withHikagePriority(shouldFillDefaultLayoutParams: Boolean): LookupElement {
        if (!isHikageFunctionLookup()) return this

        val lookupElement = if (shouldFillDefaultLayoutParams)
            DefaultLayoutParamsLookupDecorator.decorateIfNeeded(this)
        else this
        val prioritizedElement = PrioritizedLookupElement
            .withPriority(lookupElement, HIKAGABLE_PRIORITY)
            .let { PrioritizedLookupElement.withGrouping(it, HIKAGABLE_GROUPING) }
            .let { PrioritizedLookupElement.withExplicitProximity(it, HIKAGABLE_EXPLICIT_PROXIMITY) }

        // Default sorting uses several independent weighers, and same-named Android classes can
        // still win through proximity or previous lookup state. Apply every public priority signal
        // available here so classes stay available but Hikage DSL functions occupy the first rows.
        return TopPriorityLookupElement.prioritizeToTop(prioritizedElement, false)
    }

    private fun LookupElement.isHikageFunctionLookup(): Boolean {
        val declaration = psiElement as? KtCallableDeclaration
        if (declaration != null) return DeclarationMatcher.isHikagableFunction(declaration)

        return K2LookupObject.isReceiverFunction(`object`)
    }

    private fun KtElement.isInHikagePerformerScope() = runCatching {
        val file = containingKtFile
        analyze(this) {
            // A nested Hikage component can keep an outer Performer in the implicit receiver tower
            // while the current `this` has already switched to a child DSL such as HikageView<T>.
            // Only boost performer functions when the nearest receiver is the Performer itself.
            file.scopeContext(this@isInHikagePerformerScope)
                .implicitReceivers
                .nearestReceiver()
                ?.type
                ?.isHikagePerformerType() == true
        }
    }.getOrDefault(false)

    private fun List<KaImplicitReceiver>.nearestReceiver() = minByOrNull { receiver -> receiver.scopeIndexInTower }

    private fun KaType.isHikagePerformerType() = (this as? KaClassType)?.classId == Symbols.HIKAGE_PERFORMER_CLASS_ID

    private inner class HikagePerformerFunctionWeigher : LookupElementWeigher("hikagePerformerFunction") {

        override fun weigh(element: LookupElement, context: WeighingContext): Comparable<*> {
            // This weigher runs before the platform priority weigher registered by defaultSorter.
            // It separates Hikage performer functions from same-named classes before IntelliJ applies
            // the rest of Kotlin completion's relevance model.
            return if (element.isHikageFunctionLookup()) HIKAGE_PERFORMER_FUNCTION_WEIGHT else OTHER_LOOKUP_WEIGHT
        }
    }
}