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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.usageView.UsageInfo

/**
 * A refactoring processor for renaming Hikage dynamic performers.
 */
internal class HikageViewRenameRefactoringProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
    private val searchScope: SearchScope,
    private val searchInCommentsAndStrings: Boolean,
    searchInNonJavaFiles: Boolean
) : RenameProcessor(
        project,
        element,
        newName,
        searchScope,
        searchInCommentsAndStrings,
        searchInNonJavaFiles
    ) {

    private val renameProcessor = HikageViewRenameProcessor()

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: LinkedHashMap<PsiElement, String>) {
        // Kotlin's default processors add light and companion elements here.
        // The generated performer is tied to one View source declaration.
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