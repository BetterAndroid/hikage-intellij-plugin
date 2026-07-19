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
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement

/**
 * Prevents direct rename operations for generated performers that cannot be represented by a project `@HikageView` class rename.
 */
class PerformerRenameVetoCondition : Condition<PsiElement> {

    override fun value(element: PsiElement): Boolean {
        if (!ProjectGate.from(element.project).isEnabled()) return false
        val performer = HikageViewRenameSupport.findGeneratedPerformer(element)
        val renamablePerformer = HikageViewRenameSupport.findRenamablePerformer(element)

        return performer != null && renamablePerformer == null
    }
}