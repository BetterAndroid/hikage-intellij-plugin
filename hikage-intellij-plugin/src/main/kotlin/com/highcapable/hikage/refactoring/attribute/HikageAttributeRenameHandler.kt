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
package com.highcapable.hikage.refactoring.attribute

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.idea.res.psi.ResourceReferencePsiElement
import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import org.jetbrains.android.refactoring.renaming.KotlinResourceRenameHandler
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Routes Rename from Hikage attribute names and resource values through Android Studio's native resource refactoring.
 */
class HikageAttributeRenameHandler : RenameHandler, TitledHandler {

    private companion object {
        const val CANNOT_RENAME_MESSAGE = "Cannot perform refactoring.\nThis element cannot be renamed"
    }

    private val nativeHandler = KotlinResourceRenameHandler()

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val request = dataContext.findRequest()
        return request != null && ProjectGate.from(request.expression.project).isEnabled()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val request = dataContext.findRequest() ?: return
        val target = request.target
        if (target?.isProjectResource != true) {
            editor?.let { HintManager.getInstance().showErrorHint(it, CANNOT_RENAME_MESSAGE) }
            return
        }

        val resourceElement = ResourceReferencePsiElement(request.expression, target.reference, false)
            .toWritableResourceReferencePsiElement()
            ?: return

        // Android Studio's Kotlin resource handler cannot derive its synthetic resource target from a custom
        // Kotlin string reference, so only the PSI_ELEMENT slot is adapted before returning to the native chain.
        val resourceDataContext = CustomizedDataContext.withSnapshot(dataContext) { sink ->
            sink[CommonDataKeys.PSI_ELEMENT] = resourceElement
            sink[LangDataKeys.PSI_ELEMENT_ARRAY] = arrayOf(resourceElement)
        }
        nativeHandler.invoke(project, editor, request.expression.containingFile, resourceDataContext)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) = invoke(
        project,
        CommonDataKeys.EDITOR.getData(dataContext),
        CommonDataKeys.PSI_FILE.getData(dataContext),
        dataContext
    )

    override fun getActionTitle() = nativeHandler.actionTitle

    private fun DataContext.findRequest(): Request? {
        val expression = findExpression() ?: return null
        val resolver = HikageAttributeContextResolver.from(expression.project)
        if (!resolver.isRenameCandidate(expression)) return null
        val target = resolver.resolveReferenceTarget(expression)?.let { resolvedReference ->
            Target(resolvedReference.reference, resolvedReference.isProjectResource)
        }

        return Request(expression, target)
    }

    private fun DataContext.findExpression(): KtStringTemplateExpression? {
        val editor = CommonDataKeys.EDITOR.getData(this)
        val file = CommonDataKeys.PSI_FILE.getData(this)
        if (editor != null && file != null) sequenceOf(editor.caretModel.offset, editor.caretModel.offset - 1)
            .filter { offset -> offset in 0 until file.textLength }
            .mapNotNull(file::findElementAt)
            .firstNotNullOfOrNull { element -> element.findStringExpression() }
            ?.let { return it }

        return PsiElementRenameHandler.getElement(this)?.findStringExpression()
    }

    private fun PsiElement.findStringExpression() = when (this) {
        is ResourceReferencePsiElement -> (delegate as? KtStringTemplateExpression)?.takeIf(PsiElement::isValid)
        else -> PsiTreeUtil.getParentOfType(this, classOf<KtStringTemplateExpression>(), false)
            ?.takeIf(PsiElement::isValid)
    }

    private data class Request(
        val expression: KtStringTemplateExpression,
        val target: Target?
    )

    private data class Target(
        val reference: ResourceReference,
        val isProjectResource: Boolean
    )
}