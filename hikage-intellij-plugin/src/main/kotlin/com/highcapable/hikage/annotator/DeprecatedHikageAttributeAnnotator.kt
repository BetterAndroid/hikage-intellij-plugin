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
package com.highcapable.hikage.annotator

import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Highlights deprecated Android attributes with the IDE's standard deprecation text style.
 */
class DeprecatedHikageAttributeAnnotator : Annotator {

    private companion object {
        const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val call = element as? KtCallExpression ?: return
        if (call.calleeExpression?.text != HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME ||
            !ProjectGate.from(call.project).isEnabled()
        ) return

        val contextResolver = HikageAttributeContextResolver.from(call.project)
        val setCall = contextResolver.resolveSetCall(call) ?: return
        val resolver = AndroidAttributeResolver.from(call) ?: return
        holder.highlightDeprecatedName(setCall, contextResolver, resolver)
        holder.highlightDeprecatedValue(setCall, contextResolver, resolver)
    }

    private fun AnnotationHolder.highlightDeprecatedName(
        setCall: HikageAttributeContextResolver.SetCall,
        contextResolver: HikageAttributeContextResolver,
        resolver: AndroidAttributeResolver
    ) {
        val expression = setCall.nameArgument?.getArgumentExpression() ?: return
        val attributeName = contextResolver.resolveAttributeName(setCall) ?: return
        val layoutScope = if (attributeName.name.startsWith(LAYOUT_ATTRIBUTE_PREFIX))
            contextResolver.resolveScopes(setCall)?.layout
        else null
        val resolution = resolver.resolve(attributeName.namespace, attributeName.name, layoutScope)
        val attribute = (resolution as? AndroidAttributeResolver.Resolution.Found)?.attribute ?: return

        if (attribute.definition.isAttributeDeprecated) highlightDeprecated(expression)
    }

    private fun AnnotationHolder.highlightDeprecatedValue(
        setCall: HikageAttributeContextResolver.SetCall,
        contextResolver: HikageAttributeContextResolver,
        resolver: AndroidAttributeResolver
    ) {
        val expression = setCall.valueArgument?.getArgumentExpression() ?: return
        val value = contextResolver.resolveAttributeValue(setCall) ?: return
        val resolution = resolver.resolveAttributeReference(value)
        val attribute = (resolution as? AndroidAttributeResolver.Resolution.Found)?.attribute ?: return
        if (attribute.definition.isAttributeDeprecated) highlightDeprecated(expression)
    }

    private fun AnnotationHolder.highlightDeprecated(expression: KtExpression) {
        newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
            .range(expression.contentRange())
            .textAttributes(CodeInsightColors.DEPRECATED_ATTRIBUTES)
            .create()
    }

    private fun KtExpression.contentRange() = if (this is KtStringTemplateExpression)
        TextRange(textRange.startOffset + 1, textRange.endOffset - 1)
    else textRange
}