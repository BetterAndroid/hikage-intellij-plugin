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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.completion

import com.highcapable.hikage.completion.detector.HikageLayoutIdReceiverDetector
import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * Keeps automatic completion enabled while editing a Hikage layout-ID string.
 */
class HikageLayoutIdCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (psiFile.language != KotlinLanguage.INSTANCE || !ProjectGate.from(psiFile.project).isEnabled()) return ThreeState.UNSURE

        return if (HikageLayoutIdReceiverDetector.isLayoutIdString(psiFile, offset)) ThreeState.NO
        else ThreeState.UNSURE
    }
}