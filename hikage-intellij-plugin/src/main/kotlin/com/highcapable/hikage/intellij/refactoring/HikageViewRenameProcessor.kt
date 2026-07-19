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
 * This file is created by fankes on 2026/7/15.
 */
package com.highcapable.hikage.intellij.refactoring

import com.highcapable.hikage.intellij.project.ProjectGate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo

/**
 * Keeps an unaliased project `@HikageView` declaration and its generated performer in sync during rename refactorings.
 */
class HikageViewRenameProcessor : RenamePsiElementProcessor() {

    override fun createRenameDialog(
        project: Project,
        element: PsiElement,
        nameSuggestionContext: PsiElement?,
        editor: Editor?
    ) = object : RenameDialog(project, element, nameSuggestionContext, editor) {
        override fun createRenameProcessor(newName: String) = HikageViewRenameRefactoringProcessor(
            project,
            element,
            newName,
            refactoringScope,
            isSearchInComments,
            isSearchInNonJavaFiles
        )
    }

    override fun canProcessElement(element: PsiElement): Boolean {
        if (!ProjectGate.from(element.project).isEnabled()) return false
        val view = HikageViewRenameSupport.findProjectHikageView(element)
        val performer = HikageViewRenameSupport.findRenamablePerformer(element)

        return view != null || performer != null
    }

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement {
        val view = HikageViewRenameSupport.findProjectHikageView(element)
        val performer = HikageViewRenameSupport.findRenamablePerformer(element)

        return performer?.view ?: view ?: element
    }

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val references = super.findReferences(element, searchScope, searchInCommentsAndStrings)
        val view = HikageViewRenameSupport.findProjectHikageView(element)
        val performer = view?.let(HikageViewRenameSupport::findPerformerReference) ?: return references

        // Generated performer imports and calls are renamed separately so nested class names follow KSP's `Outer_Inner` convention.
        return references.filterNot { reference ->
            HikageViewRenameSupport.isPerformerReference(reference, performer)
        }
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val view = HikageViewRenameSupport.findProjectHikageView(element)
        val performer = view?.let(HikageViewRenameSupport::findRenamablePerformer)
        val references = performer?.let(HikageViewRenameSupport::collectReferences)
        val fileRename = view?.let { declaredView -> HikageViewRenameSupport.findFileRename(declaredView, newName) }

        super.renameElement(element, newName, usages, listener)

        if (view != null && performer != null && references != null) {
            val generatedFunctionName = HikageViewRenameSupport.generatedFunctionName(view)
            if (generatedFunctionName != null) references.rename(performer, generatedFunctionName)
        }
        fileRename?.rename()
    }
}