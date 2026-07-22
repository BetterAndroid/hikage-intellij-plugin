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

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.analysis.layout.model.HikageLayoutLookup
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.settings.service.SettingsService
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Presents resolved Hikage Layout ID and root lookups as compact receiver properties in the editor.
 */
class HikageLayoutLookupFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (quick || root !is KtFile || root.isScript() || DumbService.isDumb(root.project) ||
            !ProjectGate.from(root.project).isEnabled() ||
            !SettingsService.getInstance(root.project).isLayoutLookupPreviewEnabled
        ) return emptyArray()

        val resolver = HikageLayoutResolver.from(root.project)
        val descriptors = mutableListOf<FoldingDescriptor>()

        root.accept(object : KtTreeVisitorVoid() {

            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                super.visitArrayAccessExpression(expression)
                descriptors.addLookup(expression, resolver)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                descriptors.addLookup(expression, resolver)
            }
        })
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val expression = node.psi as? KtExpression ?: return null
        if (!SettingsService.getInstance(expression.project).isLayoutLookupPreviewEnabled) return null

        val resolver = HikageLayoutResolver.from(expression.project)
        val idLookup = resolver.resolveIdLookup(expression)
        if (idLookup != null) {
            if (resolver.hasIncorrectLookupType(idLookup)) return null
            return idLookup.placeholderText()
        }
        val rootLookup = resolver.resolveRootLookup(expression) ?: return null
        if (resolver.hasIncorrectLookupType(rootLookup)) return null

        return rootLookup.placeholderText()
    }

    override fun isCollapsedByDefault(node: ASTNode) = true

    private fun MutableList<FoldingDescriptor>.addLookup(
        expression: KtExpression,
        resolver: HikageLayoutResolver
    ) {
        val idLookup = resolver.resolveIdLookup(expression)
        if (idLookup != null) {
            if (!resolver.hasIncorrectLookupType(idLookup))
                addLookup(idLookup.expression, idLookup.receiver, idLookup.placeholderText())
            return
        }

        val rootLookup = resolver.resolveRootLookup(expression) ?: return
        if (!resolver.hasIncorrectLookupType(rootLookup))
            addLookup(rootLookup.expression, rootLookup.receiver, rootLookup.placeholderText())
    }

    private fun MutableList<FoldingDescriptor>.addLookup(
        expression: KtExpression,
        receiver: KtExpression,
        placeholderText: String
    ) {
        val startOffset = (expression as? KtQualifiedExpression)
            ?.operationTokenNode
            ?.textRange
            ?.endOffset
            ?: receiver.textRange.endOffset
        val range = TextRange(startOffset, expression.textRange.endOffset)
        add(FoldingDescriptor(expression.node, range, null, placeholderText))
    }

    private fun HikageLayoutLookup.placeholderText() = when (this) {
        is HikageLayoutLookup.Id -> if (expression is KtQualifiedExpression) layoutId.name else ".${layoutId.name}"
        is HikageLayoutLookup.Root -> HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME
    }
}