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
package com.highcapable.hikage.refactoring.layout

import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.completion.detector.HikageLayoutIdReceiverDetector
import com.highcapable.hikage.refactoring.layout.HikageLayoutIdRenameTargetResolver.findTarget
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.PsiElementRenameHandler
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Resolves Layout ID declarations and lookup strings into stable Rename targets.
 */
object HikageLayoutIdRenameTargetResolver {

    /**
     * Returns whether the active editor context is structurally eligible for Layout ID Rename.
     *
     * This availability check is intentionally Analysis-free. [findTarget] remains authoritative before Rename runs.
     */
    fun isPotentialTarget(dataContext: DataContext): Boolean {
        val directExpression = dataContext.findEditorStringExpression() ?: CommonDataKeys.PSI_ELEMENT.getData(dataContext)?.findStringExpression()

        return directExpression?.isPotentialTargetString() == true ||
            dataContext.findCollapsedExpression(HikageLayoutIdReceiverDetector::isPotentialLayoutIdLookup) != null
    }

    /** Returns the Layout ID Rename target represented by the active editor context. */
    fun findTarget(dataContext: DataContext): HikageLayoutIdRenameTarget? {
        val expression = dataContext.findExpression() ?: return null
        return findTarget(expression, PsiElementRenameHandler.getElement(dataContext))
    }

    /** Returns the Layout ID Rename target represented by [element]. */
    fun findTarget(element: PsiElement): HikageLayoutIdRenameTarget? {
        val expression = element.findStringExpression() ?: return null
        return findTarget(expression)
    }

    private fun findTarget(expression: KtExpression, resolvedTarget: PsiElement? = null): HikageLayoutIdRenameTarget? {
        val resolver = HikageLayoutResolver.from(expression.project)
        val id = resolver.resolveIdValue(expression)
        val layoutId = resolver.resolveIdLookup(expression)?.layoutId
            // Rename target substitution can still provide the resolved performer while K2 call resolution is
            // temporarily unavailable from the action context. Reuse that platform result for this exact ID only.
            ?: (resolvedTarget as? KtExpression)
                ?.let(resolver::resolveIdDeclaration)
                ?.takeIf { candidate -> candidate.name == id }
            ?: (expression as? KtStringTemplateExpression)?.let(resolver::resolveIdValueDeclaration)
            ?: return null

        val declaration = layoutId.declaration as? KtStringTemplateExpression ?: return null
        if (!declaration.isWritable || resolver.resolveIdValue(declaration) != layoutId.name) return null

        return HikageLayoutIdRenameTarget(declaration, layoutId.performer, layoutId.name)
    }

    private fun DataContext.findExpression(): KtExpression? {
        findEditorStringExpression()?.let { return it }
        findCollapsedExpression { expression ->
            HikageLayoutResolver.from(expression.project).resolveIdLookup(expression) != null
        }?.let { return it }

        return PsiElementRenameHandler.getElement(this)?.findStringExpression()
    }

    private fun DataContext.findEditorStringExpression(): KtStringTemplateExpression? {
        val editor = CommonDataKeys.EDITOR.getData(this) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(this) ?: return null

        return sequenceOf(editor.caretModel.offset, editor.caretModel.offset - 1)
            .filter { offset -> offset in 0 until file.textLength }
            .mapNotNull(file::findElementAt)
            .firstNotNullOfOrNull { element -> element.findStringExpression() }
    }

    private fun DataContext.findCollapsedExpression(predicate: (KtExpression) -> Boolean): KtExpression? {
        val editor = CommonDataKeys.EDITOR.getData(this) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(this) ?: return null
        val offset = editor.caretModel.offset
        val region = editor.foldingModel.getCollapsedRegionAtOffset(offset)
            ?: (offset - 1).takeIf { candidate -> candidate >= 0 }
                ?.let(editor.foldingModel::getCollapsedRegionAtOffset)
            ?: return null
        val anchorElement = file.findElementAt(region.startOffset) ?: return null

        return generateSequence(anchorElement) { element -> element.parent }
            .filterIsInstance<KtExpression>()
            .firstOrNull { expression ->
                expression.textRange.endOffset == region.endOffset &&
                    expression.textRange.containsOffset(region.startOffset) &&
                    predicate(expression)
            }
    }

    private fun KtStringTemplateExpression.isPotentialTargetString(): Boolean {
        if (!isPotentialPlainString() || HikageAttributeContextResolver.isPotentialSetString(this)) return false
        if (HikageLayoutIdReceiverDetector.isPotentialLayoutIdLookup(this)) return true

        val argument = findValueArgument() ?: return false
        val call = argument.findOwnerCall() ?: return false
        if (!HikageLayoutIdReceiverDetector.isPotentialPerformerCall(call)) return false

        if (argument.getArgumentName()?.asName?.identifier == "id") return true
        if (argument.getArgumentName() != null) return false
        return call.valueArguments.indexOf(argument) in 0..1
    }

    private fun KtStringTemplateExpression.findValueArgument() = generateSequence(parent) { element -> element.parent }
        .filterIsInstance<KtValueArgument>()
        .firstOrNull()
        ?.takeIf { argument -> argument.getArgumentExpression() === this }

    private fun KtValueArgument.findOwnerCall() = generateSequence(parent) { element -> element.parent }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtStringTemplateExpression.isPotentialPlainString(): Boolean {
        val source = text
        return source.length >= 2 && source.startsWith('"') && source.endsWith('"') &&
            !source.startsWith("\"\"\"") && !source.contains('$') && !source.contains('\\')
    }

    private fun PsiElement.findStringExpression() = PsiTreeUtil.getParentOfType(
        this,
        classOf<KtStringTemplateExpression>(),
        false
    )?.takeIf(PsiElement::isValid)
}