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

import com.highcapable.hikage.analysis.layout.model.HikageLayout
import com.highcapable.hikage.analysis.layout.model.HikageLayout.Id
import com.highcapable.hikage.analysis.layout.model.HikageLayout.Root
import com.highcapable.hikage.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Scans one performer source and extracts its statically known runtime IDs and root View.
 */
class HikageLayoutIdHelper(
    private val sourceHelper: HikageLayoutSourceHelper,
    private val typeHelper: HikageLayoutTypeHelper
) {

    private companion object {
        const val EMBEDDED_ARGUMENT = "embedded"
        const val HIKAGE_ARGUMENT = "hikage"
        const val ID_ARGUMENT = "id"
        const val PERFORMER_ARGUMENT = "performer"
        const val VIEW_ARGUMENT = "view"
        const val VIEW_CLASS_ARGUMENT = "viewClass"
        const val DELEGATE_ARGUMENT = "delegate"
    }

    /** Resolves the IDs and root represented by [source]. */
    fun resolve(source: HikageLayoutSourceHelper.Source) = scanLambda(source.performer, emptyMap(), hashSetOf()).toModel()

    /** Resolves a statically known layout ID value from [expression]. */
    fun resolveIdValue(expression: KtExpression) = expression.constantStringValue(emptyMap())

    private fun scanLambda(
        lambda: KtLambdaExpression,
        substitutions: Map<KtParameter, KtExpression>,
        visitedFunctions: MutableSet<KtNamedFunction>
    ) = lambda.bodyExpression?.let { scanExpression(it, substitutions, visitedFunctions) } ?: ScanResult()

    private fun scanExpression(
        expression: KtExpression,
        substitutions: Map<KtParameter, KtExpression>,
        visitedFunctions: MutableSet<KtNamedFunction>
    ): ScanResult = when (expression) {
        is KtParenthesizedExpression -> expression.expression
            ?.let { scanExpression(it, substitutions, visitedFunctions) }
            ?: ScanResult()
        is KtReturnExpression -> expression.returnedExpression
            ?.let { scanExpression(it, substitutions, visitedFunctions) }
            ?: ScanResult()
        is KtBlockExpression -> expression.statements.fold(ScanResult()) { result, statement ->
            result then scanExpression(statement, substitutions, visitedFunctions)
        }
        is KtIfExpression -> mergeAlternatives(
            expression.then?.let { scanExpression(it, substitutions, visitedFunctions) },
            expression.`else`?.let { scanExpression(it, substitutions, visitedFunctions) }
        )
        is KtWhenExpression -> expression.entries
            .mapNotNull { entry -> entry.expression?.let { scanExpression(it, substitutions, visitedFunctions) } }
            .reduceOrNull(::mergeAlternatives)
            ?: ScanResult()
        is KtQualifiedExpression -> (expression.selectorExpression as? KtCallExpression)
            ?.let { scanCall(it, substitutions, visitedFunctions) }
            ?: ScanResult()
        is KtCallExpression -> scanCall(expression, substitutions, visitedFunctions)
        else -> expression.children
            .filterIsInstance<KtExpression>()
            .filterNot { child -> child is KtLambdaExpression }
            .fold(ScanResult()) { result, child ->
                result then scanExpression(child, substitutions, visitedFunctions)
            }
    }

    private fun scanCall(
        call: KtCallExpression,
        substitutions: Map<KtParameter, KtExpression>,
        visitedFunctions: MutableSet<KtNamedFunction>
    ): ScanResult {
        val method = call.resolveMethod() ?: return ScanResult()
        if (!DeclarationMatcher.isHikagableFunction(method)) return ScanResult()
        if (method.isDelegateInvoke()) return scanLayoutCall(call, method, substitutions, visitedFunctions, call.calleeExpression)

        val sourceFunction = method.sourceFunction()
        val sourceBody = sourceFunction?.bodyExpression
        if (sourceFunction != null && sourceBody != null && visitedFunctions.add(sourceFunction)) {
            val nestedSubstitutions = sourceFunction.valueParameters.mapNotNull { parameter ->
                val name = parameter.name ?: return@mapNotNull null
                val argument = call.findArgument(method, name)?.getArgumentExpression() ?: return@mapNotNull null
                parameter to argument.resolveSubstitution(substitutions)
            }.toMap()
            val sourceResult = scanExpression(sourceBody, nestedSubstitutions, visitedFunctions)
            visitedFunctions.remove(sourceFunction)
            if (!sourceResult.isEmpty) return sourceResult
        }

        if (method.name == HikageSymbols.HIKAGE_LAYOUT_FUNCTION_NAME) return scanLayoutCall(call, method, substitutions, visitedFunctions)

        val viewClass = resolveViewClass(call, method, substitutions)
        val idExpression = call.findArgument(method, ID_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
        val id = idExpression?.constantStringValue(substitutions)
        val ownResult = ScanResult(
            root = viewClass?.let { Root(it, call.calleeExpression ?: call) },
            ids = if (id.isNullOrEmpty()) emptyList()
            else listOf(Id(id, viewClass, call.calleeExpression ?: call, idExpression))
        )
        val performer = call.findArgument(method, PERFORMER_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions) as? KtLambdaExpression
            ?: return ownResult

        return ownResult then scanLambda(performer, substitutions, visitedFunctions)
    }

    private fun scanLayoutCall(
        call: KtCallExpression,
        method: PsiMethod,
        substitutions: Map<KtParameter, KtExpression>,
        visitedFunctions: MutableSet<KtNamedFunction>,
        delegateOverride: KtExpression? = null
    ): ScanResult {
        val delegate = delegateOverride?.resolveSubstitution(substitutions)
            ?: call.findArgument(method, DELEGATE_ARGUMENT)
                ?.getArgumentExpression()
                ?.resolveSubstitution(substitutions)
        val hikage = call.findArgument(method, HIKAGE_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
        val childExpression = delegate ?: hikage
        val childResult = childExpression
            ?.let(sourceHelper::resolve)
            ?.map { source -> scanLambda(source.performer, emptyMap(), visitedFunctions) }
            ?.fold(ScanResult()) { result, next -> result then next }
            ?: ScanResult()
        val fallbackViewClass = call.findArgument(method, VIEW_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
            ?.let(typeHelper::resolveViewClass)
            ?: typeHelper.viewClass
        val root = childResult.root ?: fallbackViewClass?.let { viewClass ->
            Root(viewClass, call.calleeExpression ?: call)
        }
        val idExpression = call.findArgument(method, ID_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
        val id = idExpression?.constantStringValue(substitutions)
        val ownIds = if (id.isNullOrEmpty() || root == null) emptyList()
        else listOf(Id(id, root.viewClass, call.calleeExpression ?: call, idExpression))
        val embedded = delegate != null && call.findArgument(method, EMBEDDED_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
            ?.constantBooleanValue(substitutions) != false
        val childIds = childResult.ids.takeIf { delegate != null && embedded }.orEmpty()

        return ScanResult(root, ownIds + childIds)
    }

    private fun resolveViewClass(
        call: KtCallExpression,
        method: PsiMethod,
        substitutions: Map<KtParameter, KtExpression>
    ): PsiClass? {
        call.findArgument(method, VIEW_CLASS_ARGUMENT)
            ?.getArgumentExpression()
            ?.resolveSubstitution(substitutions)
            ?.let(typeHelper::resolveViewClass)
            ?.let { return it }
        typeHelper.resolveViewClass(call)?.let { return it }

        return (method.returnType as? PsiClassType)
            ?.resolve()
            ?.takeIf(typeHelper::isViewClass)
    }

    private tailrec fun KtExpression.resolveSubstitution(
        substitutions: Map<KtParameter, KtExpression>,
        visited: MutableSet<PsiElement> = hashSetOf()
    ): KtExpression {
        if (!visited.add(this)) return this
        if (this is KtParenthesizedExpression) return expression?.resolveSubstitution(substitutions, visited) ?: this
        if (this !is KtNameReferenceExpression) return this

        return when (val declaration = mainReference.resolve()) {
            is KtParameter -> substitutions[declaration]?.resolveSubstitution(substitutions, visited) ?: this
            is KtProperty -> declaration.takeUnless(KtProperty::isVar)
                ?.initializer
                ?.resolveSubstitution(substitutions, visited)
                ?: this
            else -> this
        }
    }

    private fun KtExpression.constantStringValue(
        substitutions: Map<KtParameter, KtExpression>,
        visited: MutableSet<PsiElement> = hashSetOf()
    ): String? {
        if (!visited.add(this)) return null
        if (this is KtStringTemplateExpression) {
            val isRawString = text.startsWith("\"\"\"")
            return entries.map { entry ->
                when (entry) {
                    is KtLiteralStringTemplateEntry -> if (isRawString)
                        entry.text
                    else StringUtil.unescapeStringCharacters(entry.text)
                    is KtStringTemplateEntryWithExpression -> entry.expression
                        ?.constantStringValue(substitutions, visited)
                    else -> if (isRawString) entry.text else StringUtil.unescapeStringCharacters(entry.text)
                }
            }.let { values ->
                if (values.any { value -> value == null }) null else values.joinToString(separator = "")
            }
        }

        if (this is KtParenthesizedExpression) return expression?.constantStringValue(substitutions, visited)
        if (this is KtBinaryExpression) {
            if (operationToken != KtTokens.PLUS) return null

            val left = left?.constantStringValue(substitutions, visited) ?: return null
            val right = right?.constantStringValue(substitutions, visited) ?: return null
            return left + right
        }

        val declaration = when (this) {
            is KtNameReferenceExpression -> mainReference.resolve()
            is KtQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.mainReference?.resolve()
            else -> null
        } ?: return null

        return when (declaration) {
            is KtParameter -> substitutions[declaration]?.constantStringValue(substitutions, visited)
            is KtProperty -> {
                if (declaration.isVar || !visited.add(declaration)) return null
                declaration.initializer?.constantStringValue(substitutions, visited).also { visited -= declaration }
            }
            is PsiField -> declaration.computeConstantValue() as? String
            else -> null
        }
    }

    private tailrec fun KtExpression.constantBooleanValue(
        substitutions: Map<KtParameter, KtExpression>,
        visited: MutableSet<PsiElement> = hashSetOf()
    ): Boolean? {
        if (!visited.add(this)) return null
        if (this is KtConstantExpression) return when (text) {
            "true" -> true
            "false" -> false
            else -> null
        }

        if (this is KtParenthesizedExpression) return expression?.constantBooleanValue(substitutions, visited)
        if (this !is KtNameReferenceExpression) return null

        return when (val declaration = mainReference.resolve()) {
            is KtParameter -> substitutions[declaration]?.constantBooleanValue(substitutions, visited)
            is KtProperty -> declaration.takeUnless(KtProperty::isVar)
                ?.initializer
                ?.constantBooleanValue(substitutions, visited)
            else -> null
        }
    }

    private fun mergeAlternatives(first: ScanResult?, second: ScanResult?): ScanResult {
        if (first == null) return second ?: ScanResult()
        if (second == null) return first

        val root = first.root?.takeIf { firstRoot ->
            val secondClass = second.root?.viewClass ?: return@takeIf false
            val firstQualifiedName = firstRoot.viewClass.qualifiedName
            secondClass == firstRoot.viewClass ||
                firstQualifiedName != null && secondClass.qualifiedName == firstQualifiedName
        }
        val firstIds = first.ids.groupBy(Id::name)
        val secondIds = second.ids.groupBy(Id::name)
        val alwaysPresentIds = firstIds.keys.intersect(secondIds.keys).filterTo(hashSetOf()) { name ->
            firstIds.getValue(name).any(Id::isAlwaysPresent) &&
                secondIds.getValue(name).any(Id::isAlwaysPresent)
        }
        val ids = (first.ids + second.ids).map { id ->
            id.copy(isAlwaysPresent = id.name in alwaysPresentIds)
        }

        return ScanResult(root, ids)
    }

    private fun ScanResult.toModel() = HikageLayout(ids, root)

    private fun PsiMethod.sourceFunction() = (this as? KtLightMethod)?.kotlinOrigin as? KtNamedFunction
        ?: navigationElement as? KtNamedFunction

    private fun PsiMethod.isDelegateInvoke() = name == HikageSymbols.HIKAGE_DELEGATE_INVOKE_FUNCTION_NAME &&
        parameterList.parameters.any { parameter ->
            parameter.type.canonicalClassName() == HikageSymbols.HIKAGE_DELEGATE
        }

    private data class ScanResult(
        val root: Root? = null,
        val ids: List<Id> = emptyList()
    ) {

        val isEmpty get() = root == null && ids.isEmpty()

        infix fun then(next: ScanResult) = ScanResult(root ?: next.root, ids + next.ids)
    }
}