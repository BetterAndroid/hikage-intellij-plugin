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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.model.HikageSymbols
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Matches Hikage declarations that receive IDE-specific naming behavior.
 */
object HikageDeclarationMatcher {

    private val HIKAGE_FACTORY_CALLABLE_IDS = setOf(
        HikageSymbols.HIKAGABLE_CALLABLE_ID,
        HikageSymbols.HIKAGE_CREATE_CALLABLE_ID,
        HikageSymbols.HIKAGE_BUILD_CALLABLE_ID
    )

    /** Returns true when the declaration is a Hikage DSL component function. */
    fun isHikagableFunction(declaration: KtCallableDeclaration) = declaration.hasHikagableAnnotation()

    /** Returns the layout-params parameter name when the function should receive a default `LayoutParams()` completion body. */
    fun findDefaultLayoutParamsParameterName(function: KtNamedFunction): String? {
        if (!isHikagableFunction(function)) return null

        return function.findLayoutParamsParameterName() ?: function.findLayoutParamsParameterNameText()
    }

    /** Returns true when the function should receive the default `LayoutParams()` completion body. */
    fun shouldCompleteDefaultLayoutParams(function: KtNamedFunction) = findDefaultLayoutParamsParameterName(function) != null

    /** Returns true when the property represents a Hikage layout value. */
    fun isHikageProperty(property: KtProperty): Boolean {
        val file = property.containingKtFile
        return property.hasHikageAnalysisType() ||
            property.typeReference?.isHikageType(file) == true ||
            property.initializer?.isHikagableInitializer(file) == true
    }

    /** Returns true when the property is directly initialized by a Hikage factory call. */
    fun isDirectHikageFactoryProperty(property: KtProperty): Boolean {
        if (property.hasDelegate()) return false
        return property.initializer?.isDirectHikageFactoryInitializer(property.containingKtFile) == true
    }

    private fun KtCallableDeclaration.hasHikagableAnnotation(): Boolean {
        val file = containingKtFile
        return annotationEntries.any { annotation ->
            val referenceText = annotation.typeReference?.text ?: return@any false
            referenceText == HikageSymbols.HIKAGABLE_ANNOTATION ||
                referenceText == HikageSymbols.HIKAGABLE_ANNOTATION_NAME && file.hasHikagableImport()
        }
    }

    private fun KtFile.hasHikagableImport() = importDirectives.any { directive ->
        val importedFqName = directive.importedFqName?.asString()
        importedFqName == HikageSymbols.HIKAGABLE_ANNOTATION ||
            directive.isAllUnder && importedFqName == HikageSymbols.HIKAGABLE_ANNOTATION.substringBeforeLast(".")
    }

    private fun KtNamedFunction.findLayoutParamsParameterName() = runCatching {
        analyze(this) {
            val parameters = symbol.valueParameters
            parameters.singleOrNull { parameter -> parameter.returnType.isLayoutParamsType() }?.name?.asString()
        }
    }.getOrNull()?.takeUnless(String::isBlank)

    private fun KtNamedFunction.findLayoutParamsParameterNameText() = valueParameters.singleOrNull { parameter ->
        parameter.typeReference?.isLayoutParamsType(containingKtFile) == true
    }?.name?.takeUnless(String::isBlank)

    private fun KtProperty.hasHikageAnalysisType() = runCatching {
        analyze(this) {
            returnType.isHikageType() || initializer?.expressionType?.isHikageType() == true
        }
    }.getOrDefault(false)

    private fun KtExpression.isHikagableInitializer(file: KtFile) = asCallExpression()
        ?.isHikageFactoryCall(file, setOf(HikageSymbols.HIKAGABLE_CALLABLE_ID))
        ?: false

    private fun KtExpression.isDirectHikageFactoryInitializer(file: KtFile) = asCallExpression()
        ?.isHikageFactoryCall(file, HIKAGE_FACTORY_CALLABLE_IDS)
        ?: false

    private fun KtExpression.asCallExpression(): KtCallExpression? {
        if (this is KtCallExpression) return this
        return (this as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression
    }

    private fun KtCallExpression.isHikageFactoryCall(file: KtFile, callableIds: Set<CallableId>): Boolean {
        if (hasHikageFactoryResolvedCall(callableIds)) return true
        return isHikageFactoryCallText(file, callableIds)
    }

    @OptIn(KaExperimentalApi::class)
    private fun KtCallExpression.hasHikageFactoryResolvedCall(callableIds: Set<CallableId>) = runCatching {
        analyze(this) {
            // resolveSymbol is used here to distinguish real Hikage factories from user-defined wrappers with matching names.
            val resolvedSymbol = (this@hasHikageFactoryResolvedCall as KtCallElement).resolveSymbol()
            (resolvedSymbol as? KaCallableSymbol)?.callableId in callableIds
        }
    }.getOrDefault(false)

    private fun KtCallExpression.isHikageFactoryCallText(file: KtFile, callableIds: Set<CallableId>): Boolean {
        val calleeName = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        return callableIds.any { callableId ->
            when (callableId) {
                HikageSymbols.HIKAGABLE_CALLABLE_ID -> calleeName == HikageSymbols.HIKAGABLE_FUNCTION_NAME &&
                    file.hasImport(HikageSymbols.HIKAGABLE_FUNCTION)
                HikageSymbols.HIKAGE_CREATE_CALLABLE_ID -> isHikageCompanionCall(HikageSymbols.HIKAGE_CREATE_FUNCTION_NAME, file)
                HikageSymbols.HIKAGE_BUILD_CALLABLE_ID -> isHikageCompanionCall(HikageSymbols.HIKAGE_BUILD_FUNCTION_NAME, file)
                else -> false
            }
        }
    }

    private fun KtCallExpression.isHikageCompanionCall(functionName: String, file: KtFile): Boolean {
        if ((calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != functionName) return false
        val receiver = (parent as? KtDotQualifiedExpression)?.receiverExpression?.text ?: return false

        return receiver == HikageSymbols.HIKAGE_NAME &&
            file.hasImport(HikageSymbols.HIKAGE) ||
            receiver == HikageSymbols.HIKAGE
    }

    private fun KtTypeReference.isHikageType(file: KtFile): Boolean {
        val typeElementText = typeElement?.text ?: return false

        return typeElementText == HikageSymbols.HIKAGE_NAME ||
            typeElementText.startsWith("${HikageSymbols.HIKAGE_NAME}.") ||
            typeElementText == HikageSymbols.HIKAGE ||
            typeElementText.startsWith("${HikageSymbols.HIKAGE}.") ||
            typeElementText.startsWith("${HikageSymbols.HIKAGE_DELEGATE_NAME}<") &&
            file.hasImport(HikageSymbols.HIKAGE_DELEGATE)
    }

    private fun KtTypeReference.isLayoutParamsType(file: KtFile): Boolean {
        val typeElementText = typeElement?.text ?: return false

        return typeElementText == HikageSymbols.HIKAGE_LAYOUT_PARAMS ||
            typeElementText == HikageSymbols.HIKAGE_LAYOUT_PARAMS_NAME && file.hasImport(HikageSymbols.HIKAGE_LAYOUT_PARAMS)
    }

    private fun KaType.isHikageType() = (this as? KaClassType)?.classId.let { classId ->
        classId == HikageSymbols.HIKAGE_CLASS_ID || classId == HikageSymbols.HIKAGE_DELEGATE_CLASS_ID
    }

    private fun KaType.isLayoutParamsType() =
        (this as? KaClassType)?.classId == HikageSymbols.HIKAGE_LAYOUT_PARAMS_CLASS_ID

    private fun KtFile.hasImport(fqName: String) = importDirectives.any { directive ->
        val importedFqName = directive.importedFqName?.asString()
        importedFqName == fqName || directive.isAllUnder && importedFqName == fqName.substringBeforeLast(".")
    }
}