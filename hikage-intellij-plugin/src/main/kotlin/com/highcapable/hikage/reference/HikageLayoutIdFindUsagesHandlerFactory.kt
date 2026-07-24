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
 * This file is created by fankes on 2026/7/23.
 */
package com.highcapable.hikage.reference

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.dsl.builder.PerformerSourceBuilder
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.resolveClassName
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.jetbrains.kotlin.idea.navigation.KotlinResolveExtensionGeneratedSourcesFilter
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Enables the standard Find Usages workflow for component performer names declaring Hikage Layout IDs.
 */
class HikageLayoutIdFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement) = ProjectGate.from(element.project).isEnabled() && when (element) {
        // KtNamedFunction also inherits KtExpression, so generated performers must be classified first.
        is PerformerUsageTargetElement -> element.searchTarget != null
        is KtNamedFunction -> element.isGeneratedPerformer()
        is KtExpression -> HikageLayoutResolver.from(element.project).resolveIdDeclaration(element) != null
        else -> false
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        if (!ProjectGate.from(element.project).isEnabled()) return null

        val target = resolveTarget(element) ?: return null
        val delegate = findDelegate(element, forHighlightUsages)

        return object : FindUsagesHandler(element) {

            override fun getPrimaryElements(): Array<PsiElement> = arrayOf(target)

            override fun getSecondaryElements() = delegate?.secondaryElements ?: super.getSecondaryElements()

            override fun getFindUsagesDialog(isSingleFile: Boolean, toShowInNewTab: Boolean, mustOpenInNewTab: Boolean) =
                delegate?.getFindUsagesDialog(
                    isSingleFile,
                    toShowInNewTab,
                    mustOpenInNewTab
                ) ?: super.getFindUsagesDialog(isSingleFile, toShowInNewTab, mustOpenInNewTab)

            override fun getFindUsagesOptions(dataContext: DataContext?) = delegate?.getFindUsagesOptions(dataContext)
                ?: super.getFindUsagesOptions(dataContext)

            override fun getHelpId() = delegate?.helpId ?: super.getHelpId()

            override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
                val searchTarget = (element as? PerformerUsageTargetElement)?.searchTarget ?: element
                return delegate?.processElementUsages(searchTarget, processor, options)
                    ?: super.processElementUsages(searchTarget, processor, options)
            }

            override fun findReferencesToHighlight(target: PsiElement, searchScope: SearchScope): Collection<PsiReference> {
                val searchTarget = (target as? PerformerUsageTargetElement)?.searchTarget ?: target
                return delegate?.findReferencesToHighlight(searchTarget, searchScope)
                    ?: super.findReferencesToHighlight(searchTarget, searchScope)
            }
        }
    }

    // Keep the generated-function branch before KtExpression for the same PSI inheritance constraint as canFindUsages.
    private fun resolveTarget(element: PsiElement) = when (element) {
        is PerformerUsageTargetElement -> element.takeIf { target -> target.searchTarget != null }
        is KtNamedFunction -> element.takeIf { function -> function.isGeneratedPerformer() }?.let { function ->
            PerformerUsageTargetElement(function, function.findViewNavigationTarget())
        }
        is KtExpression -> HikageLayoutResolver.from(element.project).resolveIdDeclaration(element)?.let { layoutId ->
            PerformerUsageTargetElement(layoutId.performer, layoutId.viewClass?.navigationElement)
        }
        else -> null
    }

    private fun findDelegate(element: PsiElement, forHighlightUsages: Boolean) = EP_NAME
        .getExtensionList(element.project).asSequence()
        .filter { factory -> factory !== this }
        .firstNotNullOfOrNull { factory ->
            factory.takeIf { candidate -> candidate.canFindUsages(element) }
                ?.createFindUsagesHandler(element, forHighlightUsages)
        }

    /**
     * K2 may retain a generated declaration PSI across a performer-declaration snapshot refresh. Ask the same generated
     * source filter used by Kotlin navigation, but validate the Hikage performer signature without entering Analysis API.
     */
    private fun KtNamedFunction.isGeneratedPerformer(): Boolean {
        val file = containingKtFile
        val packageName = file.packageFqName.asString()
        val packageMatches = packageName == HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX ||
            packageName.startsWith("${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.") ||
            PerformerSourceBuilder.FILE_MARKER in file.text
        val virtualFile = file.virtualFile
        val isResolveExtensionSource = virtualFile != null && GeneratedSourcesFilter.EP_NAME.extensionList
            .filterIsInstance<KotlinResolveExtensionGeneratedSourcesFilter>()
            .any { filter -> filter.isGeneratedSource(virtualFile, project) }

        return packageMatches || isResolveExtensionSource && hasPerformerSignature()
    }

    private fun KtNamedFunction.hasPerformerSignature(): Boolean {
        val file = containingKtFile
        val hasHikagableAnnotation = annotationEntries.any { annotation ->
            val typeText = annotation.typeReference?.text ?: return@any false
            typeText == HikageSymbols.HIKAGABLE_ANNOTATION ||
                file.resolveClassName(typeText) == HikageSymbols.HIKAGABLE_ANNOTATION
        }
        if (!hasHikagableAnnotation) return false

        val receiverType = receiverTypeReference?.text
            ?.substringBefore("<")
            ?.removeSuffix("?")
            ?.trim()
            ?: return false
        return receiverType == HikageSymbols.HIKAGE_PERFORMER ||
            file.resolveClassName(receiverType) == HikageSymbols.HIKAGE_PERFORMER
    }

    private fun KtNamedFunction.findViewNavigationTarget(): PsiElement? {
        val file = containingKtFile
        val viewType = typeReference?.text?.removeSuffix("?")?.trim() ?: return null
        val viewClass = file.resolveClassName(viewType) ?: return null

        return JavaPsiFacade.getInstance(project)
            .findClass(viewClass, GlobalSearchScope.allScope(project))
            ?.navigationElement
    }
}