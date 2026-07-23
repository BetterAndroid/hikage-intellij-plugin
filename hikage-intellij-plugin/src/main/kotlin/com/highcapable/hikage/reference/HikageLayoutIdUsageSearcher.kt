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
import com.highcapable.hikage.project.ProjectGate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Finds resolved Hikage Layout ID lookup strings as usages of their component performer name.
 */
class HikageLayoutIdUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {

    private companion object {
        val NON_WORD_PATTERN = "\\W+".toRegex()
    }

    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        ApplicationManager.getApplication().runReadAction {
            processQueryInReadAction(queryParameters, consumer)
        }
    }

    private fun processQueryInReadAction(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        if (!ProjectGate.from(queryParameters.project).isEnabled()) return

        val target = when (val element = queryParameters.elementToSearch) {
            is PerformerUsageTargetElement -> element.performer
            is KtExpression -> element
            else -> null
        } ?: return
        val resolver = HikageLayoutResolver.from(target.project)
        val layoutId = resolver.resolveIdDeclaration(target) ?: return

        // A component performer name is a call-site expression rather than a declaration PSI, so the platform
        // assigns it a file-local use scope. Respect the user-selected scope to keep cross-file lookups searchable.
        when (val scope = queryParameters.scopeDeterminedByUser) {
            is LocalSearchScope -> scope.scope.asSequence()
                .mapNotNull(PsiElement::getContainingFile)
                .filterIsInstance<KtFile>()
                .distinct()
                .all { file -> file.processLookups(target, layoutId.name, resolver, consumer) }
            is GlobalSearchScope -> processGlobalScope(target, layoutId.name, scope, resolver, consumer)
            else -> processGlobalScope(
                target,
                layoutId.name,
                GlobalSearchScope.projectScope(target.project),
                resolver,
                consumer
            )
        }
    }

    private fun processGlobalScope(
        target: KtExpression,
        id: String,
        scope: GlobalSearchScope,
        resolver: HikageLayoutResolver,
        consumer: Processor<in PsiReference>
    ) {
        val searchWord = id.split(NON_WORD_PATTERN)
            .filter(String::isNotEmpty)
            .maxByOrNull(String::length)
        if (searchWord == null) {
            val manager = PsiManager.getInstance(target.project)
            FileTypeIndex.processFiles(
                KotlinFileType.INSTANCE,
                { virtualFile ->
                    val ktFile = manager.findFile(virtualFile) as? KtFile ?: return@processFiles true
                    ktFile.processLookups(target, id, resolver, consumer)
                },
                scope
            )
            return
        }

        PsiSearchHelper.getInstance(target.project).processAllFilesWithWordInLiterals(
            searchWord,
            scope
        ) { file ->
            val ktFile = file as? KtFile ?: return@processAllFilesWithWordInLiterals true
            ktFile.processLookups(target, id, resolver, consumer)
        }
    }

    private fun KtFile.processLookups(
        target: KtExpression,
        id: String,
        resolver: HikageLayoutResolver,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val manager = PsiManager.getInstance(project)
        collectDescendantsOfType<KtStringTemplateExpression>().forEach { expression ->
            val lookup = resolver.resolveIdLookup(expression) ?: return@forEach
            if (lookup.layoutId.name != id ||
                !manager.areElementsEquivalent(lookup.layoutId.performer, target)
            ) return@forEach

            val reference = expression.references.firstOrNull { candidate ->
                candidate.resolve()?.let { resolved -> manager.areElementsEquivalent(resolved, target) } == true
            } ?: return@forEach
            if (!consumer.process(reference)) return false
        }

        return true
    }
}