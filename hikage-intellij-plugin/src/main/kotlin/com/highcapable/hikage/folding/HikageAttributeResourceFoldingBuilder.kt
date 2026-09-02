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
package com.highcapable.hikage.folding

import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.resources.ResourceResolver
import com.android.resources.ResourceType
import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.settings.service.SettingsService
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import org.jetbrains.android.AndroidAnnotatorUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Presents resolved Hikage attribute resource and theme references as their concrete values.
 */
class HikageAttributeResourceFoldingBuilder : FoldingBuilderEx() {

    private companion object {

        const val FOLD_MAX_LENGTH = 60

        val FOLDABLE_RESOURCE_TYPES = setOf(
            ResourceType.BOOL,
            ResourceType.COLOR,
            ResourceType.DIMEN,
            ResourceType.FRACTION,
            ResourceType.INTEGER,
            ResourceType.MACRO,
            ResourceType.STRING,
            ResourceType.STYLE_ITEM
        )
    }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (quick || root !is KtFile || root.isScript() || DumbService.isDumb(root.project) ||
            !ProjectGate.from(root.project).isEnabled() ||
            !HikageRuntimeAttributeGate.isEnabled(root) ||
            !SettingsService.getInstance(root.project).isAttributeResourceReferencePreviewEnabled
        ) return emptyArray()

        val facet = AndroidFacet.getInstance(root) ?: return emptyArray()
        val resourceResolver = AndroidAnnotatorUtil.pickConfiguration(root.originalFile, facet)
            ?.resourceResolver
            ?: return emptyArray()
        val contextResolver = HikageAttributeContextResolver.from(root.project)
        val descriptors = mutableListOf<FoldingDescriptor>()

        root.accept(object : KtTreeVisitorVoid() {

            override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                super.visitStringTemplateExpression(expression)

                if (!HikageAttributeContextResolver.isPotentialSetString(expression)) return
                val placeholderText = expression.placeholderText(contextResolver, resourceResolver) ?: return

                // Keep the string delimiters outside the collapsed range so range-based gutter annotations remain visible.
                val foldingRange = ElementManipulators.getValueTextRange(expression)
                    .shiftRight(expression.textRange.startOffset)
                descriptors += FoldingDescriptor(expression.node, foldingRange, null, placeholderText)
            }
        })

        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val expression = node.psi as? KtStringTemplateExpression ?: return null
        if (!SettingsService.getInstance(expression.project).isAttributeResourceReferencePreviewEnabled) return null

        val facet = AndroidFacet.getInstance(expression) ?: return null
        val resourceResolver = AndroidAnnotatorUtil.pickConfiguration(expression.containingFile.originalFile, facet)
            ?.resourceResolver
            ?: return null
        return expression.placeholderText(HikageAttributeContextResolver.from(expression.project), resourceResolver)
    }

    override fun isCollapsedByDefault(node: ASTNode) = true

    private fun KtStringTemplateExpression.placeholderText(
        contextResolver: HikageAttributeContextResolver,
        resourceResolver: ResourceResolver
    ) = contextResolver.resolveResourceReference(this)
        ?.let { reference -> resourceResolver.resolveConcreteValue(reference) }
        ?.let { value ->
            StringUtil.escapeStringCharacters(
                StringUtil.shortenTextWithEllipsis(value, FOLD_MAX_LENGTH - 2, 0)
            )
        }

    private fun ResourceResolver.resolveConcreteValue(reference: ResourceReference): String? {
        if (reference.resourceType == ResourceType.ID) return null

        val resolvedValue = when (reference.resourceType) {
            ResourceType.ATTR -> findItemInTheme(reference)?.let(::resolveResValue)
            else -> getResolvedResource(reference)
        } ?: return null
        val value = resolvedValue.value ?: return null
        if (resolvedValue.resourceType !in FOLDABLE_RESOURCE_TYPES || value.startsWith('@') || value.startsWith('?'))
            return null
        if (resolvedValue.resourceType == ResourceType.COLOR && !value.startsWith('#')) return null

        return value
    }
}