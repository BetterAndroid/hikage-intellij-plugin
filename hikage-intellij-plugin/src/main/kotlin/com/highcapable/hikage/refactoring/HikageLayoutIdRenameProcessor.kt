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
package com.highcapable.hikage.refactoring

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.refactoring.HikageLayoutIdRenameSupport.TargetElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Renames one resolved Hikage Layout ID declaration together with its lookup strings.
 */
class HikageLayoutIdRenameProcessor : RenamePsiElementProcessor() {

    /**
     * Keeps Layout ID Rename limited to its declaration facade and resolved string references.
     * This bypasses the platform's Kotlin-member substitution for the performer callee.
     */
    private class RefactoringProcessor(
        private val renameProcessor: HikageLayoutIdRenameProcessor,
        project: Project,
        element: PsiElement,
        newName: String,
        private val searchScope: SearchScope,
        private val searchInCommentsAndStrings: Boolean,
        searchInNonJavaFiles: Boolean
    ) : RenameProcessor(
            project, element, newName,
            searchScope,
            searchInCommentsAndStrings,
            searchInNonJavaFiles
        ) {

        override fun prepareRenaming(element: PsiElement, newName: String, allRenames: LinkedHashMap<PsiElement, String>) {
            // A Layout ID owns only its source string and resolved lookup references.
        }

        override fun findUsages(): Array<UsageInfo> = elements.singleOrNull()?.let { element ->
            renameProcessor.findReferences(element, searchScope, searchInCommentsAndStrings)
                .map { reference -> renameProcessor.createUsageInfo(element, reference, reference.element) }
                .toTypedArray()
        } ?: UsageInfo.EMPTY_ARRAY

        override fun performRefactoring(usages: Array<UsageInfo>) {
            val element = elements.singleOrNull() ?: return
            renameProcessor.renameElement(element, getNewName(element), usages, null)
        }
    }

    override fun createRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?) =
        object : RenameDialog(project, element, nameSuggestionContext, editor) {
            override fun createRenameProcessor(newName: String) = RefactoringProcessor(
                this@HikageLayoutIdRenameProcessor,
                project,
                element,
                newName,
                refactoringScope,
                isSearchInComments,
                isSearchInNonJavaFiles
            )
        }

    override fun canProcessElement(element: PsiElement) = element is TargetElement

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
        val target = element as? TargetElement ?: return emptyList()
        val performer = target.performer ?: return emptyList()
        val manager = PsiManager.getInstance(target.project)
        val resolver = HikageLayoutResolver.from(target.project)

        return ReferencesSearch.search(performer, searchScope, false).findAll().filter { reference ->
            val expression = reference.element as? KtStringTemplateExpression ?: return@filter false
            val layoutId = resolver.resolveIdLookup(expression)?.layoutId ?: return@filter false

            layoutId.name == target.name && manager.areElementsEquivalent(layoutId.performer, performer)
        }
    }
}