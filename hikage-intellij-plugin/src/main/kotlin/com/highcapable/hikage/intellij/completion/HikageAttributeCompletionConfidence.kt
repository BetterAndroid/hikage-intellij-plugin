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
 * This file is created by fankes on 2026/7/19.
 */
package com.highcapable.hikage.intellij.completion

import com.highcapable.hikage.intellij.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.intellij.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Enables automatic completion only in real Hikage attribute setter string arguments.
 */
class HikageAttributeCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (!ProjectGate.from(psiFile.project).isEnabled() || psiFile.language != KotlinLanguage.INSTANCE) return ThreeState.UNSURE

        val literal = sequenceOf(
            contextElement,
            psiFile.findElementAt((offset - 1).coerceAtLeast(0)),
            psiFile.findElementAt(offset)
        ).filterNotNull().firstNotNullOfOrNull { element ->
            PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
        } ?: return ThreeState.UNSURE

        val source = literal.text
        if (source.length < 2 || !source.startsWith('"') || !source.endsWith('"') || source.startsWith("\"\"\"") ||
            source.contains('$') || source.contains('\\')
        ) return ThreeState.UNSURE

        if (HikageAttributeContextResolver.from(psiFile.project).resolveSetCall(literal) == null) return ThreeState.UNSURE

        return ThreeState.NO
    }
}