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
package com.highcapable.hikage.intellij.analysis.layout.helper

import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.utils.extension.findArgument
import com.highcapable.hikage.intellij.utils.extension.resolveMethod
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Traces a Hikage value back to the performer lambda that created its layout session.
 */
class HikageLayoutSourceHelper(private val typeHelper: HikageLayoutTypeHelper) {

    private companion object {
        const val BUILDER_ARGUMENT = "builder"
        const val PERFORMER_ARGUMENT = "performer"
    }

    /**
     * A performer source and the PSI element used to cache analysis of that source.
     * @param anchor the stable source declaration or lambda.
     * @param performer the performer lambda to scan.
     */
    data class Source(
        val anchor: PsiElement,
        val performer: KtLambdaExpression
    )

    /** Resolves all statically traceable performer sources represented by [expression]. */
    fun resolve(expression: KtExpression) = resolveExpression(expression, hashSetOf())

    /**
     * Finds the direct performer source lexically containing [expression].
     *
     * Nested Hikage/Delegate performer lambdas form independent layout scopes.
     */
    fun findContainingSource(expression: KtExpression) = generateSequence(expression.parent) { element -> element.parent }
        .filterIsInstance<KtLambdaExpression>()
        .mapNotNull(::sourceOf)
        .firstOrNull()

    private fun resolveExpression(expression: KtExpression, visited: MutableSet<PsiElement>): List<Source> {
        if (!visited.add(expression)) return emptyList()
        return when (expression) {
            is KtParenthesizedExpression -> expression.expression?.let { resolveExpression(it, visited) }.orEmpty()
            is KtNameReferenceExpression -> resolveReference(expression, visited)
            is KtQualifiedExpression -> resolveQualifiedExpression(expression, visited)
            is KtCallExpression -> resolveCall(expression, null, visited)
            else -> emptyList()
        }
    }

    private fun resolveReference(expression: KtNameReferenceExpression, visited: MutableSet<PsiElement>) =
        when (val declaration = expression.mainReference.resolve()) {
            is KtProperty -> declaration.takeUnless(KtProperty::isVar)?.delegateExpression
                ?.let { resolveExpression(it, visited) }
                ?: declaration.takeUnless(KtProperty::isVar)
                    ?.initializer
                    ?.let { resolveExpression(it, visited) }
                    .orEmpty()
            is KtClassOrObject -> resolveBuilder(declaration, visited)
            else -> emptyList()
        }

    private fun resolveQualifiedExpression(
        expression: KtQualifiedExpression,
        visited: MutableSet<PsiElement>
    ) = when (val selector = expression.selectorExpression) {
        is KtCallExpression -> resolveCall(selector, expression.receiverExpression, visited)
        is KtNameReferenceExpression -> resolveReference(selector, visited)
        else -> emptyList()
    }

    private fun resolveCall(
        call: KtCallExpression,
        explicitReceiver: KtExpression?,
        visited: MutableSet<PsiElement>
    ): List<Source> {
        val method = call.resolveMethod() ?: return emptyList()
        if (typeHelper.isHikageSource(call))
            call.findLambdaArgument(method, PERFORMER_ARGUMENT)?.asSource()?.let { return it }
        if (method.isHikageFactory())
            return call.findLambdaArgument(method, PERFORMER_ARGUMENT)?.asSource().orEmpty()
        if (method.isLazyHikageFactory()) {
            call.findLambdaArgument(method, PERFORMER_ARGUMENT)?.asSource()?.let { return it }
            val builder = call.findArgument(method, BUILDER_ARGUMENT)?.getArgumentExpression() ?: return emptyList()
            return resolveBuilderExpression(builder, visited)
        }
        if (method.isDelegateCreate() && explicitReceiver != null)
            return resolveExpression(explicitReceiver, visited)

        val sourceFunction = method.sourceFunction() ?: return emptyList()
        if (!typeHelper.isHikageSource(explicitReceiver ?: call)) return emptyList()
        if (!visited.add(sourceFunction)) return emptyList()

        return sourceFunction.bodyExpression?.let { resolveExpression(it, visited) }.orEmpty()
    }

    private fun resolveBuilderExpression(expression: KtExpression, visited: MutableSet<PsiElement>): List<Source> =
        when (val declaration = (expression as? KtNameReferenceExpression)?.mainReference?.resolve()) {
            is KtClassOrObject -> resolveBuilder(declaration, visited)
            is KtProperty -> declaration.takeUnless(KtProperty::isVar)
                ?.initializer
                ?.let { resolveBuilderExpression(it, visited) }
                .orEmpty()
            else -> emptyList()
        }

    private fun resolveBuilder(builder: KtClassOrObject, visited: MutableSet<PsiElement>): List<Source> {
        if (!typeHelper.isBuilder(builder)) return emptyList()
        if (!visited.add(builder)) return emptyList()

        val buildFunction = builder.declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { function -> function.name == HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME }
            ?: return emptyList()
        return buildFunction.bodyExpression?.let { resolveExpression(it, visited) }.orEmpty()
    }

    private fun KtCallExpression.findLambdaArgument(method: PsiMethod, name: String) =
        findArgument(method, name)?.getArgumentExpression() as? KtLambdaExpression

    private fun sourceOf(lambda: KtLambdaExpression): Source? {
        val call = generateSequence(lambda.parent) { element -> element.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null

        return resolveCall(call, null, hashSetOf()).firstOrNull { source -> source.performer === lambda }
    }

    private fun KtLambdaExpression.asSource() = listOf(Source(this, this))

    private fun PsiMethod.sourceFunction() = (this as? KtLightMethod)?.kotlinOrigin as? KtNamedFunction
        ?: navigationElement as? KtNamedFunction

    private fun PsiMethod.isHikageFactory(): Boolean {
        val sourceFqName = sourceFunction()?.fqName?.asString()
        if (sourceFqName == HikageSymbols.HIKAGABLE_FUNCTION) return true
        if (name != HikageSymbols.HIKAGE_CREATE_FUNCTION_NAME && name != HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME)
            return containingClass?.qualifiedName == HikageSymbols.HIKAGABLE_UTILS_CLASS &&
                name == HikageSymbols.HIKAGABLE_FUNCTION_NAME

        return containingClass?.qualifiedName.let { className ->
            className == HikageSymbols.HIKAGE || className == "${HikageSymbols.HIKAGE}.Companion"
        }
    }

    private fun PsiMethod.isLazyHikageFactory() = name == HikageSymbols.HIKAGE_LAZY_FUNCTION_NAME &&
        (sourceFunction()?.fqName?.asString() == HikageSymbols.HIKAGE_LAZY_FUNCTION ||
            containingClass?.qualifiedName == HikageSymbols.HIKAGE_LAZY_UTILS_CLASS)

    private fun PsiMethod.isDelegateCreate() = name == HikageSymbols.HIKAGE_DELEGATE_CREATE_FUNCTION_NAME &&
        containingClass?.qualifiedName == HikageSymbols.HIKAGE_DELEGATE
}