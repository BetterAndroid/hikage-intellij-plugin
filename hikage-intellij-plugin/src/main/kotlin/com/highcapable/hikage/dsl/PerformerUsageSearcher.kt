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
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.dsl

import com.highcapable.hikage.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.dsl.resolve.PerformerDeclarations
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.utils.extension.resolveClassName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Finds calls to dynamic performer functions as usages of their `@HikageView` class.
 */
class PerformerUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {

    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        ApplicationManager.getApplication().runReadAction {
            processQueryInReadAction(queryParameters, consumer)
        }
    }

    private fun processQueryInReadAction(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        if (!ProjectGate.from(queryParameters.project).isEnabled()) return

        val viewDeclaration = queryParameters.elementToSearch.toHikageViewDeclaration() ?: return
        val viewClass = viewDeclaration.fqName?.asString() ?: return
        val performers = PerformerDeclarations.resolve(viewDeclaration.project)
            .filter { performer -> performer.source == Source.ANNOTATION && performer.viewClass == viewClass }
        if (performers.isEmpty()) return
        val searchScope = queryParameters.effectiveSearchScope as? GlobalSearchScope
            ?: GlobalSearchScope.projectScope(viewDeclaration.project)

        performers.forEach { performer ->
            val performerFqName = performer.generatedKey
            val functionNamesByFile = mutableMapOf<KtFile, MutableSet<String>>()
            PsiSearchHelper.getInstance(viewDeclaration.project).processAllFilesWithWord(
                performer.functionName,
                searchScope,
                { file ->
                    val ktFile = file as? KtFile ?: return@processAllFilesWithWord true
                    val functionNames = ktFile.importedPerformerNames(performerFqName, performer.generatedPackageName)
                    if (functionNames.isNotEmpty()) functionNamesByFile.getOrPut(ktFile, ::linkedSetOf).addAll(functionNames)
                    true
                },
                true
            )
            if (!functionNamesByFile.any { (file, names) -> file.processPerformerCalls(names, consumer) }) return
        }
    }

    private fun PsiElement.toHikageViewDeclaration(): KtClassOrObject? {
        val declaration = this as? KtClassOrObject ?: navigationElement as? KtClassOrObject ?: return null
        val file = declaration.containingKtFile

        return declaration.takeIf { ktClass ->
            ktClass.annotationEntries.any { annotation ->
                val typeText = annotation.typeReference?.text ?: return@any false
                typeText == HikageSymbols.HIKAGE_VIEW_ANNOTATION ||
                    file.resolveClassName(typeText) == HikageSymbols.HIKAGE_VIEW_ANNOTATION
            }
        }
    }

    private fun KtFile.importedPerformerNames(performerFqName: String, performerPackageName: String) = buildSet {
        importDirectives.forEach { directive ->
            val importedFqName = directive.importedFqName?.asString() ?: return@forEach
            when {
                importedFqName == performerFqName -> add(directive.aliasName ?: performerFqName.substringAfterLast("."))
                directive.isAllUnder && importedFqName == performerPackageName -> add(performerFqName.substringAfterLast("."))
            }
        }
    }

    private fun KtFile.processPerformerCalls(functionNames: Set<String>, consumer: Processor<in PsiReference>): Boolean {
        collectDescendantsOfType<KtCallExpression>().forEach { call ->
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@forEach
            if (callee.getReferencedName() !in functionNames) return@forEach
            if (!consumer.process(callee.mainReference)) return false
        }

        return true
    }
}