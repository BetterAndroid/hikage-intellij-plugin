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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.utils.extension

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.toUElementOfType
import java.util.concurrent.CancellationException

/**
 * Checks whether a [PsiParameter] is nullable.
 * @return [Boolean]
 */
fun PsiParameter.isNullable(): Boolean {
    if (annotations.any { annotation -> annotation.qualifiedName == Nullable::class.qualifiedName }) return true
    if (annotations.any { annotation -> annotation.qualifiedName == NotNull::class.qualifiedName }) return false

    val ktParameter = navigationElement as? KtParameter ?: return true
    return ktParameter.typeReference?.text?.trim()?.endsWith("?") == true
}

/**
 * Checks whether a [PsiParameter] is of the specified [psiClass].
 * @param psiClass the [PsiClass] to check against.
 * @return [Boolean]
 */
fun PsiParameter.isTypeOf(psiClass: PsiClass): Boolean {
    val classType = type as? PsiClassType ?: return false
    return classType.resolve() == psiClass
}

/**
 * Returns the canonical class name represented by this PSI type without resolving its declaration.
 * @return [String] or null if the type does not represent a class.
 */
tailrec fun PsiType.canonicalClassName(): String? = when (this) {
    is PsiWildcardType -> bound?.canonicalClassName()
    is PsiClassType -> canonicalText.substringBefore('<')
    else -> null
}

/**
 * Adds an import to this Kotlin file when the same class or callable is not already available.
 * @param psiFactory the Kotlin PSI factory used to create the import directive.
 * @param fqName the fully qualified class or callable name to import.
 */
fun KtFile.addImport(psiFactory: KtPsiFactory, fqName: String) {
    if (!fqName.contains('.')) return
    val packageName = fqName.substringBeforeLast('.')
    if (packageFqName.asString() == packageName) return
    if (importDirectives.any { directive ->
            val importedFqName = directive.importedFqName?.asString()
            importedFqName == fqName && directive.aliasName == null ||
                directive.isAllUnder && importedFqName == packageName
        }
    ) return

    val importDirective = psiFactory.createImportDirective(ImportPath(FqName(fqName), false))
    importList?.add(importDirective) ?: addAfter(importDirective, packageDirective)
}

/**
 * Gets the attribute argument of a [KtAnnotationEntry] by its [name] or [positionalIndex].
 * Named arguments never participate in positional fallback.
 * @param name the name of the attribute to get.
 * @param positionalIndex the positional index of the attribute to get.
 * @return [KtValueArgument] or null if not found.
 */
fun KtAnnotationEntry.attributeArgument(name: String, positionalIndex: Int): KtValueArgument? {
    val arguments = valueArgumentList?.arguments ?: return null
    return arguments.firstOrNull { argument ->
        argument.getArgumentName()?.asName?.identifier == name
    } ?: arguments.filter { argument -> argument.getArgumentName() == null }.getOrNull(positionalIndex)
}

/**
 * Resolves the method of a [KtCallExpression].
 *
 * Kotlin completion temporarily leaves incomplete qualified and array-access expressions in the
 * physical file. K2 may reject the whole containing body while resolving an unrelated call, so
 * editor features must fail open instead of leaking that transient analysis failure to the IDE.
 * @return [PsiMethod] or null if not found.
 */
fun KtCallExpression.resolveMethod() = runCatching {
    toUElementOfType<UCallExpression>()?.resolve()
}.getOrElse { error ->
    if (error is ControlFlowException || error is CancellationException) throw error
    null
}

/**
 * Gets the call argument bound to the resolved method parameter with the given [name].
 * Named and trailing-lambda arguments are matched before the positional parameter layout.
 * @param method the resolved method containing the parameter layout.
 * @param name the parameter name to find.
 * @return [KtValueArgument] or null if the argument is omitted or cannot be mapped.
 */
fun KtCallExpression.findArgument(method: PsiMethod, name: String): KtValueArgument? {
    val arguments = valueArgumentList?.arguments.orEmpty()
    arguments.firstOrNull { argument ->
        argument.getArgumentName()?.asName?.identifier == name
    }?.let { return it }

    val sourceFunction = ((method as? KtLightMethod)?.kotlinOrigin as? KtNamedFunction)
        ?: method.navigationElement as? KtNamedFunction
    sourceFunction?.valueParameters
        ?.map { parameter -> parameter.name ?: return null }
        ?.let { parameterNames -> findArgument(parameterNames, name) }
        ?.let { return it }

    findResolvedArgument(name)?.let { return it }
    if (method is KtLightMethod) return null

    return findArgument(method.parameterList.parameters.map { parameter -> parameter.name }, name)
}

private fun KtCallExpression.findArgument(parameterNames: List<String>, name: String): KtValueArgument? {
    val arguments = valueArgumentList?.arguments ?: emptyList()
    arguments.firstOrNull { it.getArgumentName()?.asName?.identifier == name }?.let { return it }
    if (parameterNames.lastOrNull() == name) lambdaArguments.singleOrNull()?.let { return it }

    return arguments
        .takeWhile { it.getArgumentName() == null }
        .withIndex()
        .firstOrNull { (index, _) -> parameterNames.getOrNull(index) == name }
        ?.value
}

private fun KtCallExpression.findResolvedArgument(name: String) = runCatching {
    val sourceArguments = valueArgumentList?.arguments.orEmpty() + lambdaArguments
    analyze(this) {
        val candidates = this@findResolvedArgument.resolveToCallCandidates()
        val applicableCandidates = candidates.filter { candidate -> candidate.isInBestCandidates }
            .ifEmpty { candidates }
        val functionCalls = applicableCandidates.map { candidate ->
            candidate.candidate as? KaFunctionCall<*> ?: return@analyze null
        }
        if (functionCalls.isEmpty()) return@analyze null

        functionCalls.map { functionCall ->
            functionCall.valueArgumentMapping.entries.firstNotNullOfOrNull { (expression, parameter) ->
                if (parameter.name.asString() != name) return@firstNotNullOfOrNull null
                sourceArguments.firstOrNull { argument -> argument.getArgumentExpression() === expression }
            }
        }.distinct().singleOrNull()
    }
}.getOrElse { error ->
    if (error is ControlFlowException || error is CancellationException) throw error
    null
}