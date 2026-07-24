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
 * This file is created by fankes on 2026/7/15.
 */
package com.highcapable.hikage.refactoring.view

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.refactoring.view.model.HikageViewRenameTarget
import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Resolves project Views and generated performers that can participate in one View Rename operation.
 */
object HikageViewRenameTargetResolver {

    private const val ALIAS_FIELD = "alias"
    private const val ALIAS_POSITION = 1

    /** Returns the project-local `@HikageView` declaration represented by [element]. */
    fun findProjectView(element: PsiElement): KtClassOrObject? {
        val declaration = element as? KtClassOrObject
            ?: element.navigationElement as? KtClassOrObject
            ?: element.findParentOfType<KtClassOrObject>()
            ?: return null
        val virtualFile = declaration.containingFile.virtualFile ?: return null
        if (!ProjectFileIndex.getInstance(declaration.project).isInContent(virtualFile)) return null

        return declaration.takeIf { it.findHikageViewAnnotation() != null }
    }

    /** Matches a generated function PSI element to the current performer declaration snapshot. */
    fun findGeneratedPerformer(element: PsiElement): PerformerDeclaration? {
        val function = element as? KtNamedFunction ?: element.navigationElement as? KtNamedFunction ?: return null
        val functionName = function.name ?: return null
        val packageName = function.containingKtFile.packageFqName.asString()
        val generatedKey = "$packageName.$functionName"

        return PerformerDeclarations.resolve(function.project).singleOrNull { declaration ->
            declaration.generatedKey == generatedKey
        }
    }

    /** Finds the annotation-derived performer associated with [view]. */
    fun findGeneratedPerformer(view: KtClassOrObject): PerformerDeclaration? {
        val viewClass = view.fqName?.asString() ?: return null
        return PerformerDeclarations.resolve(view.project).firstOrNull { declaration ->
            declaration.source == Source.ANNOTATION && declaration.viewClass == viewClass
        }
    }

    /** Returns the safe project View target represented by a generated performer element. */
    fun findRenamableTarget(element: PsiElement): HikageViewRenameTarget? {
        val performer = findGeneratedPerformer(element) ?: return null
        if (performer.source != Source.ANNOTATION) return null

        return performer.findProjectView(element.project)
            ?.takeUnless { view -> findExplicitAlias(view) != null }
            ?.let { view -> HikageViewRenameTarget(view, performer) }
    }

    /** Returns the safe generated performer target associated with [view]. */
    fun findRenamableTarget(view: KtClassOrObject) = findGeneratedPerformer(view)
        ?.takeUnless { findExplicitAlias(view) != null }
        ?.let { performer -> HikageViewRenameTarget(view, performer) }

    /** Returns the explicit generated performer alias declared by [view], if present. */
    fun findExplicitAlias(view: KtClassOrObject) = view.findHikageViewAnnotation()
        ?.explicitAliasExpression()
        ?.text
        ?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank)

    private fun PerformerDeclaration.findProjectView(project: Project) = JavaPsiFacade.getInstance(project)
        .findClass(viewClass, GlobalSearchScope.projectScope(project))
        ?.navigationElement
        ?.let(::findProjectView)

    private fun KtClassOrObject.findHikageViewAnnotation() = annotationEntries.firstOrNull { annotation ->
        DeclarationMatcher.isHikageAnnotation(annotation, HikageSymbols.HIKAGE_VIEW_ANNOTATION)
    }

    private fun KtAnnotationEntry.explicitAliasExpression() = valueArgumentList?.arguments
        ?.let { arguments ->
            arguments.firstOrNull { argument -> argument.getArgumentName()?.asName?.identifier == ALIAS_FIELD }
                ?: arguments.getOrNull(ALIAS_POSITION)?.takeIf { argument -> argument.getArgumentName() == null }
        }?.getArgumentExpression()

    private inline fun <reified T : PsiElement> PsiElement.findParentOfType() = generateSequence(this) { element -> element.parent }
        .filterIsInstance<T>()
        .firstOrNull()
}