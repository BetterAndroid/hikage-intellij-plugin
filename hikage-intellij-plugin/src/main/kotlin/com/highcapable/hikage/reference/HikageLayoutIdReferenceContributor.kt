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
import com.highcapable.kavaref.extension.classOf
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
 * Resolves static Hikage Layout ID lookup strings to the matching component performer name.
 */
class HikageLayoutIdReferenceContributor : PsiReferenceContributor() {

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

                    val resolver = HikageLayoutResolver.from(expression.project)
                    if (resolver.resolveIdLookup(expression) == null) return PsiReference.EMPTY_ARRAY

                    return arrayOf(HikageLayoutIdReference(expression))
                }
            }
        )
    }

    private class HikageLayoutIdReference(
        expression: KtStringTemplateExpression
    ) : PsiReferenceBase<KtStringTemplateExpression>(
            expression,
            ElementManipulators.getValueTextRange(expression),
            true
        ) {

        override fun resolve() = element.takeIf(PsiElement::isValid)?.let { expression ->
            HikageLayoutResolver.from(expression.project)
                .resolveIdLookup(expression)
                ?.layoutId
                ?.performer
        }

        override fun getVariants() = emptyArray<Any>()
    }
}