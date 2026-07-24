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
package com.highcapable.hikage.refactoring.layout

import com.highcapable.hikage.project.ProjectGate
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler

/**
 * Routes Rename from resolved Hikage Layout ID declarations and lookup strings through one ID refactoring.
 */
class HikageLayoutIdRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val target = HikageLayoutIdRenameTargetResolver.findTarget(dataContext) ?: return false
        return ProjectGate.from(target.project).isEnabled()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val target = HikageLayoutIdRenameTargetResolver.findTarget(dataContext) ?: return
        invoke(project, editor, target)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        val target = elements.firstNotNullOfOrNull(HikageLayoutIdRenameTargetResolver::findTarget)
            ?: HikageLayoutIdRenameTargetResolver.findTarget(dataContext)
            ?: return
        invoke(project, CommonDataKeys.EDITOR.getData(dataContext), target)
    }

    private fun invoke(project: Project, editor: Editor?, target: HikageLayoutIdRenameTarget) {
        val declaration = target.declaration ?: return
        PsiElementRenameHandler.rename(target, project, declaration, editor, null, HikageLayoutIdRenameProcessor())
    }
}