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
package com.highcapable.hikage.intellij.completion

import com.highcapable.hikage.intellij.completion.detector.HikageLayoutIdReceiverDetector
import com.highcapable.hikage.intellij.project.ProjectGate
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * Requests layout-ID completion after Kotlin creates a quoted lookup argument.
 */
class HikageLayoutIdAutoPopupHandler : TypedHandlerDelegate() {

    override fun charTyped(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '"' || file.language != KotlinLanguage.INSTANCE ||
            !ProjectGate.from(project).isEnabled() || LookupManager.getActiveLookup(editor) != null
        ) return Result.CONTINUE

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor) { committedFile ->
            HikageLayoutIdReceiverDetector.isLayoutIdString(committedFile, editor.caretModel.offset)
        }
        return Result.CONTINUE
    }
}