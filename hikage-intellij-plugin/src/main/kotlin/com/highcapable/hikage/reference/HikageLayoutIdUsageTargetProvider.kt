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
package com.highcapable.hikage.reference

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Exposes resolved Hikage Layout ID lookups and component performer names as Find Usages targets.
 */
class HikageLayoutIdUsageTargetProvider : UsageTargetProvider {

    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        val offset = TargetElementUtil.adjustOffset(file, editor.document, editor.caretModel.offset)
        val element = file.findElementAt(offset) ?: return null

        return getTargets(element)
    }

    override fun getTargets(psiElement: PsiElement): Array<UsageTarget>? {
        if (!ProjectGate.from(psiElement.project).isEnabled()) return null

        val resolver = HikageLayoutResolver.from(psiElement.project)
        val target = generateSequence(psiElement) { element -> element.parent }
            .filterIsInstance<KtExpression>()
            .firstNotNullOfOrNull { expression ->
                resolver.resolveIdLookup(expression)?.layoutId?.performer
                    ?: resolver.resolveIdDeclaration(expression)?.performer
            }
            ?: return null

        return arrayOf(PsiElement2UsageTargetAdapter(target, false))
    }
}