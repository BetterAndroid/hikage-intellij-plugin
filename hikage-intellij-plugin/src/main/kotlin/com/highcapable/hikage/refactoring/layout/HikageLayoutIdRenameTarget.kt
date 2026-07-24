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

import com.intellij.icons.AllIcons
import com.intellij.ide.presentation.Presentation
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Named Rename target backed by one resolved Hikage Layout ID declaration and its performer.
 *
 * Kotlin string literals are usages rather than named declarations. This facade prevents Rename from
 * substituting the resolved performer callee and entering Kotlin's member in-place renamer.
 */
@Presentation(typeName = "Hikage Layout ID")
class HikageLayoutIdRenameTarget(
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