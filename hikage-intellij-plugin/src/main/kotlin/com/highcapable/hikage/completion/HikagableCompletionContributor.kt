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
package com.highcapable.hikage.completion

import com.highcapable.hikage.completion.decorator.DefaultLayoutParamsLookupDecorator
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.generated.PluginProperties
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.settings.service.SettingsService
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
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Ranks `Hikagable` completions in direct performer scopes and hides them in nested non-performer scopes.
 */
class HikagableCompletionContributor : CompletionContributor() {

    companion object {

        private const val HIKAGABLE_PRIORITY = 1_000_000.0
        private const val HIKAGABLE_GROUPING = -1_000_000
        private const val HIKAGABLE_EXPLICIT_PROXIMITY = -1_000_000
        private const val HIKAGE_PERFORMER_FUNCTION_WEIGHT = 0
        private const val OTHER_LOOKUP_WEIGHT = 1
        private const val PRIORITY_WEIGHER_ID = "priority"

        val functionLookupKey = Key.create<Boolean>("${PluginProperties.PROJECT_PLUGIN_ID}.hikagableFunctionLookup")
        val receiverFunctionLookupKey = Key.create<Boolean>("${PluginProperties.PROJECT_PLUGIN_ID}.hikagableReceiverFunctionLookup")
        val classifierLookupKey = Key.create<Boolean>("${PluginProperties.PROJECT_PLUGIN_ID}.classifierLookup")
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!ProjectGate.from(parameters.position.project).isEnabled() ||
            parameters.completionType != CompletionType.BASIC
        ) {
            super.fillCompletionVariants(parameters, result)
            return
        }

        when (parameters.findHikagePerformerScope()) {
            DeclarationMatcher.PerformerScope.NONE -> {
                super.fillCompletionVariants(parameters, result)
                return
            }
            DeclarationMatcher.PerformerScope.OUTER -> {
                result.runRemainingContributors(parameters, false).forEach { completionResult ->
                    if (!completionResult.lookupElement.isHikagableFunctionLookup())
                        result.passResult(completionResult)
                }
                result.stopHere()
                return
            }
            DeclarationMatcher.PerformerScope.NEAREST -> Unit
        }

        val hikageWeigher = HikagePerformerFunctionWeigher()
        val hikageSorter = CompletionSorter
            .defaultSorter(parameters, result.prefixMatcher)
            .weighBefore(PRIORITY_WEIGHER_ID, hikageWeigher)
        val hikageResult = result.withRelevanceSorter(hikageSorter)
        val shouldFillDefaultLayoutParams = SettingsService
            .getInstance(parameters.position.project)
            .isDefaultLayoutParamsAutoCompletionEnabled
        val completionResults = result.runRemainingContributors(parameters, false)

        // Kotlin completion may stream classifier candidates before extension function candidates.
        // Passing items one by one lets the lookup arrange and preselect the early class row before
        // the Hikage performer function exists in the list, so collect the remaining contributors
        // first and add them as matched batches. Grouping by matcher preserves contributors that
        // replace only a segment of the source, such as resource and flag completion in strings.
        completionResults.groupBy { completionResult -> completionResult.prefixMatcher }.forEach { (matcher, results) ->
            hikageResult.withPrefixMatcher(matcher).addAllElements(
                results.map { completionResult ->
                    completionResult.lookupElement.withHikagePriority(shouldFillDefaultLayoutParams)
                }
            )
        }
        result.stopHere()
    }

    private fun CompletionParameters.findHikagePerformerScope(): DeclarationMatcher.PerformerScope {
        val position = position
        if (position.language != KotlinLanguage.INSTANCE) return DeclarationMatcher.PerformerScope.NONE
        val ktPosition = PsiTreeUtil.getParentOfType(position, classOf<KtElement>(), false)
            ?: return DeclarationMatcher.PerformerScope.NONE

        return DeclarationMatcher.findHikagePerformerScope(ktPosition)
    }

    private fun LookupElement.isHikagableFunctionLookup() = when (val declaration = psiElement) {
        is KtCallableDeclaration -> DeclarationMatcher.isHikagableFunction(declaration)
        is PsiMethod -> DeclarationMatcher.isHikagableFunction(declaration)
        else -> false
    }

    private fun LookupElement.withHikagePriority(shouldFillDefaultLayoutParams: Boolean): LookupElement {
        val psiElement = psiElement
        if (psiElement.isClassifierLookup()) putUserData(classifierLookupKey, true)
        val declaration = psiElement as? KtCallableDeclaration ?: return this
        if (!DeclarationMatcher.isHikagableFunction(declaration)) return this

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
        return TopPriorityLookupElement.prioritizeToTop(prioritizedElement, false).also { element ->
            element.putUserData(functionLookupKey, true)
            if (declaration.receiverTypeReference != null) element.putUserData(receiverFunctionLookupKey, true)
        }
    }

    private fun PsiElement?.isClassifierLookup() = this is PsiClass || this is KtClassOrObject || this is KtTypeAlias

    private class HikagePerformerFunctionWeigher : LookupElementWeigher("hikagePerformerFunction") {

        override fun weigh(element: LookupElement, context: WeighingContext): Comparable<*> {
            // This weigher runs before the platform priority weigher registered by defaultSorter.
            // It separates Hikage performer functions from same-named classes before IntelliJ applies
            // the rest of Kotlin completion's relevance model.
            return if (element.getUserData(functionLookupKey) == true)
                HIKAGE_PERFORMER_FUNCTION_WEIGHT
            else OTHER_LOOKUP_WEIGHT
        }
    }
}