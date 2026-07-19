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
package com.highcapable.hikage.intellij.reference

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.idea.res.psi.ResourceReferencePsiElement
import com.highcapable.hikage.intellij.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.intellij.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Resolves static Hikage attribute names and resource values to Android Studio resource PSI.
 */
class HikageAttributeResourceReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(classOf<KtStringTemplateExpression>()),
            object : PsiReferenceProvider() {

                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val expression = element as? KtStringTemplateExpression ?: return PsiReference.EMPTY_ARRAY
                    if (!ProjectGate.from(expression.project).isEnabled()) return PsiReference.EMPTY_ARRAY

                    HikageAttributeContextResolver.from(expression.project)
                        .resolveReference(expression) ?: return PsiReference.EMPTY_ARRAY
                    val range = ElementManipulators.getValueTextRange(expression)

                    return arrayOf(HikageResourceReference(expression, range))
                }
            }
        )
    }

    private fun KtStringTemplateExpression.resourceNameRange(reference: ResourceReference): TextRange? {
        val valueRange = ElementManipulators.getValueTextRange(this)
        val value = text.substring(valueRange.startOffset, valueRange.endOffset)
        val name = reference.name
        val start = value.length - name.length
        if (start < 0 || !value.endsWith(name)) return null
        if (start > 0 && value[start - 1] != '/' && value[start - 1] != ':') return null

        return TextRange(valueRange.startOffset + start, valueRange.endOffset)
    }

    private inner class HikageResourceReference(
        expression: KtStringTemplateExpression,
        range: TextRange
    ) : PsiReferenceBase<KtStringTemplateExpression>(
            expression, range, true
        ) {

        override fun resolve() = element.takeIf(PsiElement::isValid)?.let { expression ->
            HikageAttributeContextResolver.from(expression.project)
                .resolveReference(expression)
                ?.let { reference -> ResourceReferencePsiElement(expression, reference, false) }
        }

        override fun getVariants() = emptyArray<Any>()

        override fun handleElementRename(newElementName: String): PsiElement {
            val reference = HikageAttributeContextResolver.from(element.project)
                .resolveReference(element)
                ?: return element
            val range = element.resourceNameRange(reference) ?: return element
            return ElementManipulators.handleContentChange(element, range, newElementName)
        }
    }
}