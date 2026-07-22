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
 * This file is created by fankes on 2026/7/18.
 */
package com.highcapable.hikage.inspection.base

import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor

/**
 * Runs Hikage inspections only when the opened project depends on `hikage-core`.
 */
abstract class BaseInspectionTool : LocalInspectionTool() {

    final override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        ProjectGate.from(holder.project).runIfEnabled(PsiElementVisitor.EMPTY_VISITOR) {
            createVisitor(holder, isOnTheFly)
        }

    /**
     * Creates the inspection visitor after the project capability gate succeeds.
     */
    protected abstract fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor
}