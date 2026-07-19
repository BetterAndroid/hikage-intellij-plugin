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
 * This file is created by fankes on 2026/7/20.
 */
package com.highcapable.hikage.intellij.refactoring

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.actions.RenameElementAction

/**
 * Intercepts the platform Rename action for Hikage attribute reference sites.
 * All unrelated Rename behavior remains owned by the original platform action.
 */
class HikageAttributeRenameAction : DumbAwareAction(RefactoringBundle.message("rename.title")), ActionPromoter {

    init {
        setInjectedContext(true)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) = RenameElementAction().update(event)

    override fun actionPerformed(event: AnActionEvent) {
        val handler = HikageAttributeRenameHandler()
        if (!handler.isAvailableOnDataContext(event.dataContext)) {
            RenameElementAction().actionPerformed(event)
            return
        }

        val project = event.project ?: return
        WriteIntentReadAction.run {
            BaseRefactoringAction.performRefactoringAction(project, event.dataContext, handler)
        }
    }

    override fun promote(actions: List<AnAction>, context: DataContext) = listOf(this).takeIf { this in actions }
}