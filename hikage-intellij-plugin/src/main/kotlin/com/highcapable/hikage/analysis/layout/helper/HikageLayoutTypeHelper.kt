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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.analysis.layout.helper

import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiClassType
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.toUElementOfType

/**
 * Resolves the runtime classes needed by Hikage layout-ID analysis.
 */
class HikageLayoutTypeHelper(project: Project) {

    /**
     * A Kotlin source reference for a resolved layout View type.
     * @param reference the name safe to write in the target file.
     * @param importFqName the class import to add, or null when it is already available.
     */
    data class Reference(
        val reference: String,
        val importFqName: String?
    )

    private val searchScope = GlobalSearchScope.allScope(project)

    /** The Android View base class available to the current project. */
    val viewClass by lazy(LazyThreadSafetyMode.NONE) {
        JavaPsiFacade.getInstance(project).findClass(AndroidSymbols.VIEW_CLASS, searchScope)
    }

    private val builderClass by lazy(LazyThreadSafetyMode.NONE) {
        JavaPsiFacade.getInstance(project).findClass(HikageSymbols.HIKAGE_BUILDER, searchScope)
    }

    /** Returns whether [expression] has the real Hikage runtime type. */
    fun isHikage(expression: KtExpression) = expression.canonicalClassName() == HikageSymbols.HIKAGE

    /** Returns whether [expression] has the real Hikage delegate type. */
    fun isDelegate(expression: KtExpression) = expression.canonicalClassName() == HikageSymbols.HIKAGE_DELEGATE

    /** Returns whether [expression] is either a Hikage instance or its reusable delegate. */
    fun isHikageSource(expression: KtExpression) = isHikage(expression) || isDelegate(expression)

    /** Returns whether [declaration] implements the real Hikage layout builder contract. */
    fun isBuilder(declaration: KtClassOrObject): Boolean {
        val builderClass = builderClass ?: return false
        val lightClass = declaration.toLightClass() ?: return false

        return lightClass == builderClass || lightClass.isInheritor(builderClass, true)
    }

    /** Resolves the concrete Kotlin Builder class represented by [expression]. */
    fun resolveBuilderDeclaration(expression: KtExpression): KtClassOrObject? {
        val expressionClass = when (expression) {
            is KtObjectLiteralExpression -> expression.objectDeclaration.toLightClass()
            is KtCallExpression -> expression.resolveMethod()?.let { method ->
                if (method.isConstructor) method.containingClass else (method.returnType as? PsiClassType)?.resolve()
            }
            else -> null
        } ?: (expression.toUElementOfType<UExpression>()?.getExpressionType() as? PsiClassType)?.resolve()
        val kotlinClass = expressionClass?.navigationElement as? KtClassOrObject ?: return null

        return kotlinClass.takeIf(::isBuilder)
    }

    /** Resolves the View class represented by [expression]. */
    fun resolveViewClass(expression: KtExpression): PsiClass? {
        expression.resolveClassLiteral()?.takeIf(::isViewClass)?.let { return it }
        return (expression.toUElementOfType<UExpression>()?.getExpressionType() as? PsiClassType)
            ?.resolve()
            ?.takeIf(::isViewClass)
    }

    /** Returns whether [psiClass] is an Android View class. */
    fun isViewClass(psiClass: PsiClass): Boolean {
        val viewClass = viewClass ?: return false
        return psiClass == viewClass || psiClass.isInheritor(viewClass, true)
    }

    /** Resolves the class represented by a Kotlin [typeReference]. */
    fun resolveTypeClass(typeReference: KtTypeReference): PsiClass? {
        val declaration = analyze(typeReference) {
            (typeReference.type as? KaClassType)?.symbol?.psi
        } ?: return null

        return when (declaration) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass()
            else -> null
        }
    }

    /** Creates a source reference for [viewClass] in [file]. */
    fun createTypeReference(file: KtFile, viewClass: PsiClass): Reference? {
        val qualifiedName = viewClass.qualifiedName
        val simpleName = viewClass.name ?: return null
        if (qualifiedName == null) return Reference(simpleName, null)

        file.importDirectives.firstOrNull { directive ->
            directive.importedFqName?.asString() == qualifiedName
        }?.let { directive ->
            return Reference(directive.aliasName ?: simpleName, null)
        }
        val packageName = viewClass.topLevelPackageName()
        if (viewClass.containingClass == null && file.packageFqName.asString() == packageName)
            return Reference(simpleName, null)

        return Reference(simpleName, qualifiedName)
    }

    private fun KtExpression.canonicalClassName() =
        (toUElementOfType<UExpression>()?.getExpressionType() as? PsiClassType)
            ?.resolve()
            ?.qualifiedName

    private fun KtExpression.resolveClassLiteral(): PsiClass? {
        val receiver = (this as? KtClassLiteralExpression)?.receiverExpression
            as? KtNameReferenceExpression
            ?: return null

        return when (val declaration = receiver.mainReference.resolve()) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass()
            else -> null
        }
    }

    private fun PsiClass.topLevelPackageName(): String? {
        val topLevel = generateSequence(this) { psiClass -> psiClass.containingClass }.last()
        val ownerPackage = (topLevel.containingFile as? PsiClassOwner)?.packageName
        if (!ownerPackage.isNullOrBlank()) return ownerPackage

        return topLevel.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")
    }
}