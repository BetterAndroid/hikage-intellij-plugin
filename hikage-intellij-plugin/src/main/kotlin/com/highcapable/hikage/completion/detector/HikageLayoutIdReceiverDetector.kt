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
package com.highcapable.hikage.completion.detector

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.kavaref.extension.classOf
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Finds layout-ID string contexts and verifies their receiver through the shared layout resolver.
 */
internal object HikageLayoutIdReceiverDetector {

    private val CORE_PERFORMER_FUNCTIONS = setOf(
        HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
        HikageSymbols.HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION,
        "${HikageSymbols.HIKAGE_LAYOUT_PACKAGE}.${HikageSymbols.HIKAGE_LAYOUT_FUNCTION_NAME}"
    )
    private val LAYOUT_ID_LOOKUP_FUNCTION_NAMES = setOf(
        HikageSymbols.HIKAGE_GET_FUNCTION_NAME,
        HikageSymbols.HIKAGE_GET_OR_NULL_FUNCTION_NAME
    )

    /** Returns whether [offset] is structurally eligible to contain a layout-ID string. */
    fun isPotentialLayoutIdString(file: PsiFile, offset: Int) =
        file.findLayoutIdString(offset)?.findLayoutIdLookupParts()?.isPotential() == true

    /** Returns whether [expression] has the source shape of a Hikage layout-ID lookup. */
    fun isPotentialLayoutIdLookup(expression: KtExpression) = expression.findLayoutIdLookupParts()?.isPotential() == true

    /** Returns whether [expression] can be a Hikage layout receiver without semantic analysis. */
    fun isPotentialLayoutReceiver(expression: KtExpression) = expression.isPotentialLayoutValue(hashSetOf(), 0)

    /** Returns whether [call] has the source shape of a Hikage performer declaration call. */
    fun isPotentialPerformerCall(call: KtCallExpression): Boolean {
        val calleeName = call.calleeExpression?.text?.substringAfterLast('.') ?: return false
        if (DeclarationMatcher.isPotentialGeneratedPerformerImport(call.containingKtFile, calleeName)) return true

        val file = call.containingKtFile
        if (CORE_PERFORMER_FUNCTIONS.any { symbol ->
                DeclarationMatcher.isPotentialHikageImport(file, symbol, calleeName)
            }
        ) return true

        if (call.isInsidePotentialLayoutFactory()) return true
        if (call.isInsidePotentialPerformerReceiver()) return true

        return generateSequence(call.parent) { element -> element.parent }
            .filterIsInstance<KtNamedFunction>()
            .any { function -> function.name == calleeName && DeclarationMatcher.isHikagableFunction(function) }
    }

    /** Returns whether [offset] is inside a known layout-ID string. */
    fun isLayoutIdString(file: PsiFile, offset: Int): Boolean {
        val literal = file.findLayoutIdString(offset) ?: return false
        val receiver = literal.findLayoutIdLookupParts()?.receiver ?: return false
        return HikageLayoutResolver.from(file.project).resolve(receiver) != null
    }

    private fun PsiFile.findLayoutIdString(offset: Int): KtStringTemplateExpression? {
        val literal = sequenceOf(offset - 1, offset)
            .filter { candidate -> candidate >= 0 }
            .firstNotNullOfOrNull { candidate ->
                findElementAt(candidate)?.let { element ->
                    PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
                }
            }
            ?: return null
        return literal.takeIf { expression -> expression.isPlainString() }
    }

    private fun KtStringTemplateExpression.isPlainString(): Boolean {
        val source = text
        return source.length >= 2 && source.startsWith('"') && source.endsWith('"') &&
            !source.startsWith("\"\"\"") && '$' !in source && '\\' !in source
    }

    private data class LookupParts(
        val receiver: KtExpression,
        val idExpression: KtExpression
    ) {

        fun isPotential() = idExpression is KtStringTemplateExpression &&
            idExpression.isPlainString() &&
            HikageLayoutIdReceiverDetector.isPotentialLayoutReceiver(receiver)
    }

    private fun KtExpression.findLayoutIdLookupParts(): LookupParts? {
        layoutIdLookupParts()?.let { return it }
        return generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtExpression>()
            .mapNotNull { expression -> expression.layoutIdLookupParts() }
            .firstOrNull { parts -> PsiTreeUtil.isAncestor(parts.idExpression, this, false) }
    }

    private fun KtExpression.layoutIdLookupParts(): LookupParts? = when (this) {
        is KtArrayAccessExpression -> {
            val receiver = arrayExpression ?: return null
            val idExpression = indexExpressions.singleOrNull() ?: return null
            LookupParts(receiver, idExpression)
        }
        is KtCallExpression -> (parent as? KtQualifiedExpression)
            ?.takeIf { qualified -> qualified.selectorExpression === this }
            ?.layoutIdLookupParts()
        is KtQualifiedExpression -> {
            val call = selectorExpression as? KtCallExpression ?: return null
            if (operationSign != KtTokens.DOT || call.calleeExpression?.text !in LAYOUT_ID_LOOKUP_FUNCTION_NAMES) return null
            val idExpression = call.valueArguments.firstOrNull()
                ?.takeIf { argument -> argument.getArgumentExpression() is KtStringTemplateExpression }
                ?.getArgumentExpression()
                ?: return null
            LookupParts(receiverExpression, idExpression)
        }
        else -> null
    }

    private fun KtExpression.isPotentialLayoutValue(visited: MutableSet<PsiElement>, depth: Int): Boolean {
        if (depth > 5 || !visited.add(this)) return false
        return when (val expression = this) {
            is KtParenthesizedExpression -> expression.expression?.isPotentialLayoutValue(visited, depth + 1) == true
            is KtCallExpression -> expression.isPotentialLayoutFactoryCall() ||
                expression.isPotentialBuilderBuildCall()
            is KtQualifiedExpression -> ((expression.selectorExpression as? KtCallExpression)
                ?.let { call -> call.isPotentialLayoutFactoryCall() || call.isPotentialBuilderBuildCall() }
                == true)
            is KtNameReferenceExpression -> expression.isPotentialLayoutReference(visited, depth)
            else -> false
        }
    }

    private fun KtCallExpression.isPotentialLayoutFactoryCall() =
        DeclarationMatcher.isPotentialHikageFactoryCall(this)

    private fun KtCallExpression.isPotentialBuilderBuildCall(): Boolean {
        if (calleeExpression?.text != HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME) return false
        val qualified = parent as? KtQualifiedExpression ?: return false
        if (qualified.selectorExpression !== this) return false
        val receiver = qualified.receiverExpression
        if (receiver.text == HikageSymbols.HIKAGE_NAME &&
            DeclarationMatcher.isPotentialHikageImport(
                containingKtFile,
                HikageSymbols.HIKAGE,
                HikageSymbols.HIKAGE_NAME
            )
        ) return true

        val declaration = (receiver as? KtNameReferenceExpression)?.findPotentialClassDeclaration()
        if (declaration?.hasPotentialBuilderSupertype() == true) return true
        return receiver.text.firstOrNull()?.isUpperCase() == true &&
            DeclarationMatcher.isPotentialHikageImport(
                containingKtFile,
                HikageSymbols.HIKAGE_BUILDER,
                HikageSymbols.HIKAGE_BUILDER.substringAfterLast('.')
            )
    }

    private fun KtNameReferenceExpression.isPotentialLayoutReference(
        visited: MutableSet<PsiElement>,
        depth: Int
    ): Boolean {
        findPotentialParameter()?.let { parameter ->
            if (parameter.typeReference?.isPotentialLayoutType() == true) return true
            if (getReferencedName() == "hikage" && parameter.isPotentialHikageCallbackParameter()) return true
        }

        val declaration = findPotentialCallableDeclaration() ?: return false
        return when (declaration) {
            is KtProperty -> declaration.typeReference?.isPotentialLayoutType() == true ||
                declaration.initializer?.isPotentialLayoutValue(visited, depth + 1) == true ||
                declaration.delegateExpression?.isPotentialLayoutValue(visited, depth + 1) == true
            is KtParameter -> declaration.typeReference?.isPotentialLayoutType() == true
            else -> false
        }
    }

    private fun KtTypeReference.isPotentialLayoutType() =
        DeclarationMatcher.isPotentialHikageType(this) ||
            DeclarationMatcher.isPotentialHikageBuilderType(this)

    private fun KtNameReferenceExpression.findPotentialCallableDeclaration(): KtCallableDeclaration? {
        val name = getReferencedName()
        return generateSequence(parent) { element -> element.parent }
            .firstNotNullOfOrNull { container ->
                when (container) {
                    is KtBlockExpression -> container.statements
                        .filterIsInstance<KtCallableDeclaration>()
                        .firstOrNull { declaration -> declaration.name == name }
                    is KtClassOrObject -> container.declarations
                        .filterIsInstance<KtCallableDeclaration>()
                        .firstOrNull { declaration -> declaration.name == name }
                    is KtFile -> container.declarations
                        .filterIsInstance<KtCallableDeclaration>()
                        .firstOrNull { declaration -> declaration.name == name }
                    else -> null
                }
            }
    }

    private fun KtNameReferenceExpression.findPotentialParameter(): KtParameter? {
        val name = getReferencedName()
        return generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtLambdaExpression>()
            .firstNotNullOfOrNull { lambda -> lambda.valueParameters.firstOrNull { parameter -> parameter.name == name } }
            ?: generateSequence(parent) { element -> element.parent }
                .filterIsInstance<KtNamedFunction>()
                .firstNotNullOfOrNull { function -> function.valueParameters.firstOrNull { parameter -> parameter.name == name } }
    }

    private fun KtParameter.isPotentialHikageCallbackParameter(): Boolean {
        val lambda = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtLambdaExpression>()
            .firstOrNull()
            ?: return false
        val ownerCall = lambda.findOwnerCall() ?: return false
        val calleeName = ownerCall.calleeExpression?.text?.substringAfterLast('.') ?: return false
        val rootPackage = HikageSymbols.HIKAGE_PACKAGE.substringBeforeLast('.')
        return ownerCall.containingKtFile.importDirectives.any { directive ->
            val imported = directive.importedFqName?.asString() ?: return@any false
            val referenced = directive.aliasName ?: imported.substringAfterLast('.')
            referenced == calleeName && imported.startsWith("$rootPackage.")
        }
    }

    private fun KtNameReferenceExpression.findPotentialClassDeclaration(): KtClassOrObject? {
        val name = getReferencedName()
        return generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtClassOrObject>()
            .firstOrNull { declaration -> declaration.name == name }
            ?: containingKtFile.declarations
                .filterIsInstance<KtClassOrObject>()
                .firstOrNull { declaration -> declaration.name == name }
    }

    private fun KtClassOrObject.hasPotentialBuilderSupertype() = superTypeListEntries.any { entry ->
        val typeText = entry.typeReference?.text ?: return@any false
        typeText == HikageSymbols.HIKAGE_BUILDER ||
            typeText == HikageSymbols.HIKAGE_BUILDER.substringAfterLast('.') &&
            DeclarationMatcher.isPotentialHikageImport(
                containingKtFile,
                HikageSymbols.HIKAGE_BUILDER,
                HikageSymbols.HIKAGE_BUILDER.substringAfterLast('.')
            )
    }

    private fun KtCallExpression.isInsidePotentialLayoutFactory() =
        generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtLambdaExpression>()
            .any { lambda -> lambda.findOwnerCall()?.isPotentialLayoutFactoryCall() == true }

    private fun KtCallExpression.isInsidePotentialPerformerReceiver() =
        generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtNamedFunction>()
            .any { function ->
                val receiverType = function.receiverTypeReference?.text ?: return@any false
                receiverType.contains(HikageSymbols.HIKAGE_PERFORMER_NAME) &&
                    (receiverType.startsWith(HikageSymbols.HIKAGE_PERFORMER) ||
                        DeclarationMatcher.isPotentialHikageImport(
                            function.containingKtFile,
                            HikageSymbols.HIKAGE_PERFORMER,
                            receiverType.substringAfterLast('.')
                        )
                    )
            }

    private fun KtLambdaExpression.findOwnerCall() = generateSequence(parent) { element -> element.parent }
        .takeWhile { element -> element !is KtLambdaExpression }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()
}