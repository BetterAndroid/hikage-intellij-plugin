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
package com.highcapable.hikage.completion

import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Triggers value completion after resource or theme-reference prefixes are typed.
 * The context check runs only after the platform commits PSI.
 */
class HikageAttributeAutoPopupHandler : TypedHandlerDelegate() {

    private companion object {
        const val RESOURCE_PREFIX = '@'
        const val THEME_ATTRIBUTE_PREFIX = '?'
    }

    override fun charTyped(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if ((charTyped != RESOURCE_PREFIX && charTyped != THEME_ATTRIBUTE_PREFIX) ||
            file.language != KotlinLanguage.INSTANCE || !ProjectGate.from(project).isEnabled() ||
            LookupManager.getActiveLookup(editor) != null
        ) return Result.CONTINUE

        // Kotlin schedules its annotation/label completion for '@' in beforeCharTyped, after checkAutoPopup.
        // Scheduling both prefixes here ensures the Hikage request is the final request for the typed character.
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor) { committedFile ->
            committedFile.isHikageAttributeValue(editor.caretModel.offset)
        }
        return Result.CONTINUE
    }

    private fun PsiFile.isHikageAttributeValue(offset: Int): Boolean {
        val literal = sequenceOf(offset - 1, offset)
            .filter { candidate -> candidate >= 0 }
            .firstNotNullOfOrNull { candidate ->
                findElementAt(candidate)?.let { element ->
                    PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
                }
            }
            ?: return false
        val source = literal.text
        if (source.length < 2 || !source.startsWith('"') || !source.endsWith('"') ||
            source.startsWith("\"\"\"") || source.contains('$') || source.contains('\\')
        ) return false

        val setCall = HikageAttributeContextResolver.from(project).resolveSetCall(literal) ?: return false
        val valueExpression = setCall.valueArgument?.getArgumentExpression() ?: return false

        return PsiTreeUtil.isAncestor(valueExpression, literal, false)
    }
}