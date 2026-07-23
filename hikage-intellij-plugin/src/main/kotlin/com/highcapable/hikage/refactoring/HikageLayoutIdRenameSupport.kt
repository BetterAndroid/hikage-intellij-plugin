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
package com.highcapable.hikage.refactoring

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.kavaref.extension.classOf
import com.intellij.icons.AllIcons
import com.intellij.ide.presentation.Presentation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.PsiElementRenameHandler
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Resolves Layout ID declarations and lookup strings into one stable Rename target.
 */
internal object HikageLayoutIdRenameSupport {

    /**
     * Named facade for a Layout ID string declaration.
     *
     * Kotlin string literals are usages rather than named declarations. This facade prevents Rename from
     * substituting the resolved performer callee and entering Kotlin's member in-place renamer.
     */
    @Presentation(typeName = "Hikage Layout ID")
    class TargetElement(
        declaration: KtStringTemplateExpression,
        performer: KtExpression,
        private var targetName: String
    ) : FakePsiElement(), PsiNamedElement {

        private val targetProject = declaration.project
        private val declarationPointer = SmartPointerManager.getInstance(targetProject).createSmartPsiElementPointer(declaration)
        private val performerPointer = SmartPointerManager.getInstance(targetProject).createSmartPsiElementPointer(performer)

        val declaration get() = declarationPointer.element
        val performer get() = performerPointer.element

        override fun getParent() = declaration?.parent
        override fun getProject() = targetProject
        override fun getContainingFile() = declaration?.containingFile
        override fun getName() = targetName
        override fun getPresentableText() = targetName
        override fun getIcon(unused: Boolean) = AllIcons.Nodes.Property
        override fun getNavigationElement() = declaration ?: this
        override fun getUseScope() = GlobalSearchScope.projectScope(targetProject)
        override fun isValid() = declaration != null && performer != null
        override fun isWritable() = declaration?.isWritable == true

        override fun setName(name: String): PsiElement {
            val expression = declaration ?: return this
            ElementManipulators.handleContentChange(
                expression,
                ElementManipulators.getValueTextRange(expression),
                name
            )
            targetName = name

            return this
        }
    }

    /** Returns the Layout ID Rename target represented by the active editor context. */
    fun findTarget(dataContext: DataContext): TargetElement? {
        val expression = dataContext.findExpression() ?: return null
        return findTarget(expression, PsiElementRenameHandler.getElement(dataContext))
    }

    /** Returns the Layout ID Rename target represented by [element]. */
    fun findTarget(element: PsiElement): TargetElement? {
        val expression = element.findStringExpression() ?: return null
        return findTarget(expression)
    }

    private fun findTarget(expression: KtExpression, resolvedTarget: PsiElement? = null): TargetElement? {
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

        return TargetElement(declaration, layoutId.performer, layoutId.name)
    }

    private fun DataContext.findExpression(): KtExpression? {
        val editor = CommonDataKeys.EDITOR.getData(this)
        val file = CommonDataKeys.PSI_FILE.getData(this)
        if (editor != null && file != null) sequenceOf(editor.caretModel.offset, editor.caretModel.offset - 1)
            .filter { offset -> offset in 0 until file.textLength }
            .mapNotNull(file::findElementAt)
            .firstNotNullOfOrNull { element -> element.findStringExpression() }
            ?.let { return it }

        if (editor != null && file != null) {
            val offset = editor.caretModel.offset
            val region = editor.foldingModel.getCollapsedRegionAtOffset(offset)
                ?: (offset - 1).takeIf { candidate -> candidate >= 0 }
                    ?.let(editor.foldingModel::getCollapsedRegionAtOffset)
            val anchorElement = region?.let { collapsedRegion -> file.findElementAt(collapsedRegion.startOffset) }
            if (region != null && anchorElement != null) generateSequence(anchorElement) { element -> element.parent }
                .filterIsInstance<KtExpression>()
                .firstOrNull { expression ->
                    expression.textRange.endOffset == region.endOffset &&
                        expression.textRange.containsOffset(region.startOffset) &&
                        HikageLayoutResolver.from(expression.project).resolveIdLookup(expression) != null
                }
                ?.let { return it }
        }

        return PsiElementRenameHandler.getElement(this)?.findStringExpression()
    }

    private fun PsiElement.findStringExpression() = PsiTreeUtil.getParentOfType(
        this,
        classOf<KtStringTemplateExpression>(),
        false
    )?.takeIf(PsiElement::isValid)
}