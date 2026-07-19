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
package com.highcapable.hikage.intellij.documentation

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.idea.editing.documentation.AndroidDocumentationProvider
import com.android.tools.idea.res.psi.ResourceReferencePsiElement
import com.android.tools.idea.res.psi.ResourceRepositoryToPsiResolver
import com.highcapable.hikage.intellij.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.intellij.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.psi.createPsiDocumentationTarget
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Provides Android Studio-native documentation for static Hikage attribute names and resource values.
 */
class HikageAttributeResourceDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!ProjectGate.from(file.project).isEnabled()) return emptyList()

        val expression = sequenceOf(offset, offset - 1)
            .filter { candidate -> candidate in 0 until file.textLength }
            .mapNotNull { candidate -> file.findElementAt(candidate) }
            .firstNotNullOfOrNull { element ->
                PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
            }
            ?: return emptyList()
        val reference = HikageAttributeContextResolver.from(file.project).resolveReference(expression)
            ?: return emptyList()

        return listOf(ResourceDocumentationTarget(expression, reference))
    }

    private class ResourceDocumentationTarget(
        private val expression: KtStringTemplateExpression,
        private val reference: ResourceReference
    ) : DocumentationTarget {

        private val documentationProvider = AndroidDocumentationProvider()
        private val resourceElement = ResourceReferencePsiElement(expression, reference, false)
        private val expressionPointer = SmartPointerManager.getInstance(expression.project).createSmartPsiElementPointer(expression)

        override fun createPointer() = Pointer {
            val expression = expressionPointer.element ?: return@Pointer null
            val reference = HikageAttributeContextResolver.from(expression.project)
                .resolveReference(expression)
                ?: return@Pointer null

            ResourceDocumentationTarget(expression, reference)
        }

        override fun computePresentation() = createPsiDocumentationTarget(
            resolveResourceDeclaration() ?: resourceElement,
            expression
        ).computePresentation()

        override fun computeDocumentationHint() = renderHtml() ?: fallbackHtml()
        override fun computeDocumentation() = DocumentationResult.documentation(renderHtml() ?: fallbackHtml())

        /**
         * A resource PSI element inherits its location from the Hikage string delegate. Resolve the real Android
         * resource declaration through the repository-backed PSI resolver so the presentation reports the owning
         * SDK, project resource, or dependency AAR instead of the current module's synthetic R field.
         */
        private fun resolveResourceDeclaration() = ResourceRepositoryToPsiResolver
            .getGotoDeclarationTargets(reference, expression)
            .firstOrNull()

        private fun fallbackHtml() = DocumentationMarkup.DEFINITION_START +
            StringUtil.escapeXmlEntities(reference.resourceUrl.toString()) +
            DocumentationMarkup.DEFINITION_END

        /**
         * Android's provider is registered for Java PSI only, while Hikage attribute strings are Kotlin PSI.
         * The custom target keeps Kotlin hover discovery active, then delegates rendering to Android Studio's
         * provider directly so its resource value, drawable preview, and documentation layout stay authoritative.
         */
        private fun renderHtml() = documentationProvider.generateDoc(resourceElement, expression)
    }
}