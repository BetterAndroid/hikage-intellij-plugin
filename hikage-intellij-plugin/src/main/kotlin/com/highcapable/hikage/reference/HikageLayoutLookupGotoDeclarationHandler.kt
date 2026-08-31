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
import com.highcapable.hikage.analysis.layout.model.HikageLayoutLookup
import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Preserves collapsed Layout lookup navigation and prioritizes ID usages from component performers.
 */
class HikageLayoutLookupGotoDeclarationHandler : GotoDeclarationHandler {

    private class LayoutIdUsageNavigationTarget(usage: KtExpression, id: String) : FakePsiElement() {

        private val targetProject = usage.project
        private val targetName = "\"$id\""
        private val usagePointer = SmartPointerManager.getInstance(targetProject).createSmartPsiElementPointer(usage)

        private val usage get() = usagePointer.element

        override fun getParent() = usage
        override fun getProject() = targetProject
        override fun getContainingFile() = usage?.containingFile
        override fun getName() = targetName
        override fun getPresentableText() = targetName
        override fun getIcon(unused: Boolean) = AllIcons.Nodes.Property
        override fun getNavigationElement() = usage ?: this
        override fun isValid() = usage != null
        override fun isWritable() = false
    }

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val sourceElement = sourceElement ?: return null
        if (!ProjectGate.from(sourceElement.project).isEnabled()) return null

        val resolver = HikageLayoutResolver.from(sourceElement.project)
        val performer = generateSequence(sourceElement) { element -> element.parent }
            .filterIsInstance<KtExpression>().firstNotNullOfOrNull { expression ->
                resolver.resolveIdDeclaration(expression)?.let { layoutId -> expression to layoutId }
            }
        if (performer != null) {
            val (expression, layoutId) = performer
            val lookupTargets = ReferencesSearch.search(
                expression,
                GlobalSearchScope.projectScope(sourceElement.project),
                false
            ).findAll().mapNotNull { reference ->
                val usage = reference.element as? KtExpression ?: return@mapNotNull null
                val lookup = resolver.resolveIdLookup(usage) ?: return@mapNotNull null
                LayoutIdUsageNavigationTarget(usage, lookup.layoutId.name)
            }

            val viewTarget = layoutId.viewClass?.navigationElement
            if (lookupTargets.isNotEmpty()) return (lookupTargets + listOfNotNull(viewTarget)).toTypedArray()
        }

        val region = editor.foldingModel.getCollapsedRegionAtOffset(offset)
            ?: (offset - 1).takeIf { candidate -> candidate >= 0 }
                ?.let(editor.foldingModel::getCollapsedRegionAtOffset)
            ?: editor.foldingModel.getCollapsedRegionAtOffset(sourceElement.textRange.startOffset)
            ?: return null
        val anchorElement = sourceElement.containingFile.findElementAt(region.startOffset) ?: sourceElement
        val lookup = generateSequence(anchorElement) { element -> element.parent }
            .filterIsInstance<KtExpression>()
            .mapNotNull { expression ->
                resolver.resolveIdLookup(expression) ?: resolver.resolveRootLookup(expression)
            }
            .firstOrNull { candidate ->
                candidate.expression.textRange.endOffset == region.endOffset &&
                    candidate.expression.textRange.containsOffset(region.startOffset)
            }
            ?: return null

        val target = when (lookup) {
            is HikageLayoutLookup.Id -> {
                if (resolver.hasIncorrectLookupType(lookup)) return null
                lookup.layoutId.performer
            }
            is HikageLayoutLookup.Root -> {
                if (resolver.hasIncorrectLookupType(lookup)) return null
                lookup.layoutRoot.declaration
            }
        }

        return arrayOf(target)
    }
}