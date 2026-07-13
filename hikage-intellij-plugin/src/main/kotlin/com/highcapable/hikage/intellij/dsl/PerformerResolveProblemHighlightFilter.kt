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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.intellij.dsl

import com.highcapable.hikage.intellij.dsl.builder.PerformerSourceBuilder
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile

/**
 * Skips ordinary inspections for IDE-only Hikage K2 resolve stubs.
 */
class PerformerResolveProblemHighlightFilter : ProblemHighlightFilter() {

    override fun shouldHighlight(psiFile: PsiFile): Boolean {
        if (psiFile !is KtFile) return true
        if (!psiFile.packageFqName.asString().startsWith("${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.")) return true

        return PerformerSourceBuilder.FILE_MARKER !in psiFile.text
    }
}