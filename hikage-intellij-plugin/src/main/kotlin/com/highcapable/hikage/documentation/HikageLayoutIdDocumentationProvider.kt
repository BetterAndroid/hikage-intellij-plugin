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
package com.highcapable.hikage.documentation

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.lang.documentation.psi.createPsiDocumentationTarget
import com.intellij.lang.java.JavaDocumentationProvider
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Presents the actual View declaration represented by a resolved Hikage Layout ID lookup string.
 */
class HikageLayoutIdDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!ProjectGate.from(file.project).isEnabled()) return emptyList()

        val resolver = HikageLayoutResolver.from(file.project)
        val stringExpression = sequenceOf(offset, offset - 1)
            .filter { candidate -> candidate in 0 until file.textLength }
            .mapNotNull(file::findElementAt)
            .firstNotNullOfOrNull { element ->
                PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
            }
        val lookup = stringExpression?.let(resolver::resolveIdLookup) ?: return emptyList()
        val viewClass = lookup.layoutId.viewClass
            ?: return emptyList()

        return listOf(LayoutIdDocumentationTarget(lookup.idExpression, viewClass))
    }

    private class LayoutIdDocumentationTarget(
        private val expression: KtExpression,
        private val viewClass: PsiClass
    ) : DocumentationTarget {

        private val documentationProvider = JavaDocumentationProvider()
        private val expressionPointer = SmartPointerManager.getInstance(expression.project).createSmartPsiElementPointer(expression)

        override fun createPointer() = Pointer {
            val expression = expressionPointer.element ?: return@Pointer null
            val viewClass = HikageLayoutResolver.from(expression.project)
                .resolveIdLookup(expression)
                ?.layoutId
                ?.viewClass
                ?: return@Pointer null

            LayoutIdDocumentationTarget(expression, viewClass)
        }

        override val navigatable get() = delegate().navigatable

        override fun computePresentation() = delegate().computePresentation()
        override fun computeDocumentationHint(): String? = renderHtml() ?: fallbackHtml()
        override fun computeDocumentation() = DocumentationResult.documentation(renderHtml() ?: fallbackHtml())

        private fun delegate() = createPsiDocumentationTarget(viewClass, expression)

        /**
         * Project-generated and in-memory View classes may not expose documentation through the generic PSI target.
         * Resolve hover from the Java class provider directly, matching the working attrs documentation adapter.
         */
        private fun renderHtml() = documentationProvider.generateDoc(viewClass, expression)

        private fun fallbackHtml() = documentationProvider.getQuickNavigateInfo(viewClass, expression)
            ?: JavaDocumentationProvider.generateClassInfo(viewClass)
    }
}