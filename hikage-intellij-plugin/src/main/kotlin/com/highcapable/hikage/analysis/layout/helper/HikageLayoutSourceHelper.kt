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

import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

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
        .firstNotNullOfOrNull(::sourceOf)

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
            is KtParameter -> resolveCallbackSource(expression, declaration, visited)
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
        if (method.isLayoutInvoke()) {
            val receiver = explicitReceiver ?: call.calleeExpression ?: return emptyList()
            return resolveExpression(receiver, visited)
        }
        if (method.isBuilderBuild() && explicitReceiver != null) {
            val sources = resolveBuilderExpression(explicitReceiver, visited)
            if (sources.isNotEmpty()) return sources
        }

        val sourceFunction = method.sourceFunction() ?: return emptyList()
        if (!typeHelper.isHikageSource(explicitReceiver ?: call)) return emptyList()
        if (!visited.add(sourceFunction)) return emptyList()

        return sourceFunction.bodyExpression?.let { resolveExpression(it, visited) }.orEmpty()
    }

    private fun resolveBuilderExpression(expression: KtExpression, visited: MutableSet<PsiElement>): List<Source> {
        val initializer = (expression as? KtNameReferenceExpression)
            ?.mainReference
            ?.resolve()
            ?.let { declaration -> declaration as? KtProperty }
            ?.takeUnless(KtProperty::isVar)
            ?.initializer
        if (initializer != null) {
            val sources = resolveBuilderExpression(initializer, visited)
            if (sources.isNotEmpty()) return sources
        }

        val builder = typeHelper.resolveBuilderDeclaration(expression) ?: return emptyList()
        return resolveBuilder(builder, visited)
    }

    private fun resolveBuilder(builder: KtClassOrObject, visited: MutableSet<PsiElement>): List<Source> {
        if (!typeHelper.isBuilder(builder)) return emptyList()
        if (!visited.add(builder)) return emptyList()

        val buildFunction = builder.toLightClass()
            ?.findMethodsByName(HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME, true)
            ?.firstNotNullOfOrNull { method ->
                method.takeIf { candidate -> candidate.isBuilderBuild() }
                    ?.sourceFunction()
                    ?.takeIf { function -> function.bodyExpression != null }
            }
            ?: return emptyList()
        return buildFunction.bodyExpression?.let { resolveExpression(it, visited) }.orEmpty()
    }

    private fun resolveCallbackSource(
        expression: KtNameReferenceExpression,
        parameter: KtParameter,
        visited: MutableSet<PsiElement>
    ): List<Source> {
        if (!typeHelper.isHikage(expression)) return emptyList()
        val lambda = generateSequence(parameter.parent) { element -> element.parent }
            .filterIsInstance<KtLambdaExpression>()
            .firstOrNull()
            ?: return emptyList()
        val callbackArgument = lambda.valueArgument() ?: return emptyList()
        val call = callbackArgument.ownerCall() ?: return emptyList()
        val method = call.resolveMethod() ?: return emptyList()
        if (call.findParameter(callbackArgument, method) == null) return emptyList()

        // A runtime callback can expose the Hikage created from a Delegate, Builder, or direct performer input.
        // Infer that link only when the resolved call has exactly one statically traceable layout input.
        val sources = (call.valueArgumentList?.arguments.orEmpty() + call.lambdaArguments)
            .filterNot { argument -> argument === callbackArgument }
            .mapNotNull { argument ->
                val argumentExpression = argument.getArgumentExpression() ?: return@mapNotNull null
                val parameter = call.findParameter(argument, method) ?: return@mapNotNull null
                resolveLayoutInput(argumentExpression, parameter.type, HashSet(visited)).takeIf { it.isNotEmpty() }
            }
        if (sources.size != 1) return emptyList()

        return sources.single()
    }

    private fun resolveLayoutInput(
        expression: KtExpression,
        parameterType: PsiType,
        visited: MutableSet<PsiElement>
    ) = if (parameterType.isHikagePerformerType())
        resolvePerformerInput(expression, visited)
    else resolveExpression(expression, visited)

    private fun resolvePerformerInput(
        expression: KtExpression,
        visited: MutableSet<PsiElement>
    ): List<Source> {
        if (!visited.add(expression)) return emptyList()
        return when (expression) {
            is KtParenthesizedExpression -> expression.expression
                ?.let { nested -> resolvePerformerInput(nested, visited) }
                .orEmpty()
            is KtLambdaExpression -> expression.asSource()
            is KtNameReferenceExpression -> (expression.mainReference.resolve() as? KtProperty)
                ?.takeUnless(KtProperty::isVar)
                ?.initializer
                ?.let { initializer -> resolvePerformerInput(initializer, visited) }
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun KtCallExpression.findLambdaArgument(method: PsiMethod, name: String) =
        findArgument(method, name)?.getArgumentExpression() as? KtLambdaExpression

    private fun KtLambdaExpression.valueArgument() = generateSequence(parent) { element -> element.parent }
        .takeWhile { element -> element !is KtCallExpression && element !is KtNamedFunction }
        .filterIsInstance<KtValueArgument>()
        .firstOrNull()

    private fun KtValueArgument.ownerCall() = generateSequence(parent) { element -> element.parent }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtCallExpression.findParameter(argument: KtValueArgument, method: PsiMethod) =
        method.parameterList.parameters.firstOrNull { parameter ->
            findArgument(method, parameter.name) === argument
        }

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

    private fun PsiMethod.isBuilderBuild() = name == HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME &&
        parameterList.parameters.isEmpty() &&
        returnType?.canonicalClassName() == HikageSymbols.HIKAGE_DELEGATE

    private fun PsiType.isHikagePerformerType() = canonicalText.contains(HikageSymbols.HIKAGE_PERFORMER)

    private fun PsiMethod.isLayoutInvoke() = name == HikageSymbols.HIKAGE_DELEGATE_INVOKE_FUNCTION_NAME &&
        (sourceFunction()?.fqName?.asString() == HikageSymbols.HIKAGE_LAYOUT_INVOKE_FUNCTION ||
            containingClass?.qualifiedName == HikageSymbols.HIKAGE_LAYOUT_UTILS_CLASS) &&
        parameterList.parameters.any { parameter ->
            parameter.type.canonicalClassName() in setOf(HikageSymbols.HIKAGE_DELEGATE, HikageSymbols.HIKAGE_BUILDER)
        }
}