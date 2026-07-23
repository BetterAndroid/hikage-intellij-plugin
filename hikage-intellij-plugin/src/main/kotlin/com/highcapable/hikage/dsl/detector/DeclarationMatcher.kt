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
package com.highcapable.hikage.dsl.detector

import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.highcapable.hikage.utils.extension.resolveClassName
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.components.KaImplicitReceiver
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Matches Hikage declarations that receive IDE-specific naming behavior.
 */
object DeclarationMatcher {

    private val HIKAGE_FACTORY_CALLABLE_IDS = setOf(
        HikageSymbols.HIKAGABLE_CALLABLE_ID,
        HikageSymbols.HIKAGE_CREATE_CALLABLE_ID,
        HikageSymbols.HIKAGE_BUILD_CALLABLE_ID
    )

    /** Returns true when the declaration is a Hikage DSL component function. */
    fun isHikagableFunction(declaration: KtCallableDeclaration) = declaration.annotationEntries.any { annotation ->
        isHikageAnnotation(annotation, HikageSymbols.HIKAGABLE_ANNOTATION)
    }

    /** Returns true when the resolved method represents a Hikage DSL component function. */
    fun isHikagableFunction(method: PsiMethod) = (method.navigationElement as? KtCallableDeclaration)
        ?.let(::isHikagableFunction) == true || method.annotations.any { annotation ->
        isHikageAnnotation(annotation, HikageSymbols.HIKAGABLE_ANNOTATION)
    }

    /** Returns true when the resolved symbol represents a Hikage DSL component function. */
    fun isHikagableFunction(symbol: KaCallableSymbol) = (symbol as? KaAnnotated)?.annotations
        ?.contains(HikageSymbols.HIKAGABLE_ANNOTATION_CLASS_ID) == true

    /**
     * Returns whether [element] is inside a `Hikage.Performer` receiver scope.
     *
     * When [includeOuterReceivers] is false, only the nearest implicit receiver is considered.
     */
    fun isInHikagePerformerScope(element: KtElement, includeOuterReceivers: Boolean = false) = runCatching {
        analyze(element) { isInHikagePerformerScope(this, element, includeOuterReceivers) }
    }.getOrDefault(false)

    /**
     * Returns whether [element] is inside a `Hikage.Performer` receiver scope.
     *
     * An outer performer receiver may remain visible while a nested View initializer becomes the
     * current receiver. [includeOuterReceivers] allows callers that only need scope membership to
     * accept that outer performer, while the default keeps direct performer DSL call-site checks.
     */
    fun isInHikagePerformerScope(
        session: KaSession,
        element: KtElement,
        includeOuterReceivers: Boolean = false
    ) = with(session) {
        val receivers = element.containingKtFile
            .scopeContext(element)
            .implicitReceivers
        if (includeOuterReceivers)
            receivers.any { receiver ->
                (receiver.type as? KaClassType)?.classId == HikageSymbols.HIKAGE_PERFORMER_CLASS_ID
            }
        else {
            val receiver = receivers.minByOrNull(KaImplicitReceiver::scopeIndexInTower)
            (receiver?.type as? KaClassType)?.classId == HikageSymbols.HIKAGE_PERFORMER_CLASS_ID
        }
    }

    /** Returns true when the resolved method is the Hikage performer `LayoutParams` function. */
    fun isHikageLayoutParamsFunction(method: PsiMethod) = method.name == HikageSymbols.HIKAGE_LAYOUT_PARAMS_NAME &&
        method.parameterList.parameters.firstOrNull()?.type?.canonicalClassName() == HikageSymbols.HIKAGE_PERFORMER

    /** Returns true when the resolved symbol is a Hikage layout parameters builder. */
    fun isHikageLayoutParamsFunction(symbol: KaCallableSymbol) = symbol.callableId in HikageSymbols.HIKAGE_LAYOUT_PARAMS_CALLABLE_IDS

    /** Returns true when the resolved symbol is a Hikage resources scope function. */
    fun isHikageResourcesScopeFunction(symbol: KaCallableSymbol) =
        symbol.callableId?.classId == HikageSymbols.HIKAGE_RESOURCES_SCOPE_CLASS_ID

    /** Returns true when the resolved symbol is a colored Hikage attribute factory or namespace function. */
    fun isHikageAttributeFunction(symbol: KaCallableSymbol) =
        symbol.callableId in HikageSymbols.HIKAGE_ATTRIBUTE_CALLABLE_IDS

    /** Returns true when the resolved method is the Hikage attribute factory. */
    fun isHikageAttributeFactoryFunction(method: PsiMethod) = method.name == HikageSymbols.HIKAGE_ATTRIBUTE_NAME &&
        method.containingClass?.qualifiedName == HikageSymbols.HIKAGE_ATTRIBUTE_UTILS_CLASS

    /** Returns true when the resolved method declares a Hikage attribute namespace. */
    fun isHikageAttributeNamespaceFunction(method: PsiMethod) = method.name == HikageSymbols.HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION_NAME &&
        method.containingClass?.qualifiedName == HikageSymbols.HIKAGE_ATTRIBUTE_UTILS_CLASS

    /** Returns the namespace represented by a resolved Hikage `android` or `app` shortcut. */
    fun findHikageAttributeNamespace(declaration: PsiElement): String? {
        val sourceDeclaration = (declaration.navigationElement as? KtCallableDeclaration)
            ?: declaration as? KtCallableDeclaration
        when (sourceDeclaration?.fqName?.asString()) {
            HikageSymbols.HIKAGE_ATTRIBUTE_ANDROID -> return HikageSymbols.HIKAGE_ATTRIBUTE_ANDROID.substringAfterLast('.')
            HikageSymbols.HIKAGE_ATTRIBUTE_APP -> return HikageSymbols.HIKAGE_ATTRIBUTE_APP.substringAfterLast('.')
        }

        val method = declaration as? PsiMethod ?: return null
        if (method.containingClass?.qualifiedName != HikageSymbols.HIKAGE_ATTRIBUTE_NAMESPACE_UTILS_CLASS) return null

        return listOf(HikageSymbols.HIKAGE_ATTRIBUTE_ANDROID, HikageSymbols.HIKAGE_ATTRIBUTE_APP)
            .map { fqName -> fqName.substringAfterLast('.') }
            .firstOrNull { namespace ->
                method.name == namespace || method.name == "get${namespace.replaceFirstChar(Char::uppercaseChar)}"
            }
    }

    /** Returns true when the resolved symbol is a Hikage attribute setter. */
    fun isHikageAttributeSetFunction(symbol: KaCallableSymbol) = symbol.callableId in HikageSymbols.HIKAGE_ATTRIBUTE_SET_CALLABLE_IDS

    /** Returns true when the Kotlin declaration is a Hikage attribute setter. */
    fun isHikageAttributeSetFunction(declaration: KtCallableDeclaration) = declaration.fqName?.asString().let { fqName ->
        fqName == HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION ||
            fqName == "${HikageSymbols.HIKAGE_ATTRIBUTE_SCOPE_CLASS}.${HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME}"
    }

    /** Returns true when the resolved method is a Hikage attribute setter. */
    fun isHikageAttributeSetFunction(method: PsiMethod) = isHikageRootAttributeSetFunction(method) || isHikageScopedAttributeSetFunction(method)

    /** Returns true when the resolved method sets an attribute on the root Hikage attribute receiver. */
    fun isHikageRootAttributeSetFunction(method: PsiMethod) = method.name == HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME &&
        method.containingClass?.qualifiedName == HikageSymbols.HIKAGE_ATTRIBUTE_UTILS_CLASS

    /** Returns true when the resolved method sets an attribute inside a Hikage namespace scope. */
    fun isHikageScopedAttributeSetFunction(method: PsiMethod) = method.name == HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME &&
        method.containingClass?.qualifiedName == HikageSymbols.HIKAGE_ATTRIBUTE_SCOPE_CLASS

    /** Returns whether an annotation entry resolves to the given Hikage annotation. */
    fun isHikageAnnotation(annotation: KtAnnotationEntry, annotationFqName: String): Boolean {
        val referenceText = annotation.typeReference?.text ?: return false
        return referenceText == annotationFqName ||
            annotation.containingKtFile.resolveClassName(referenceText) == annotationFqName
    }

    /** Returns whether a resolved PSI annotation matches the given Hikage annotation. */
    fun isHikageAnnotation(annotation: PsiAnnotation, annotationFqName: String) = annotation.qualifiedName == annotationFqName

    /** Returns the layout-params parameter name when the function should receive a default `LayoutParams()` completion body. */
    fun findDefaultLayoutParamsParameterName(function: KtNamedFunction): String? {
        if (function.name == HikageSymbols.HIKAGE_LAYOUT_FUNCTION_NAME) return null
        if (!isHikagableFunction(function)) return null

        return function.findLayoutParamsParameterName() ?: function.findLayoutParamsParameterNameText()
    }

    /** Returns true when the function should receive the default `LayoutParams()` completion body. */
    fun shouldCompleteDefaultLayoutParams(function: KtNamedFunction) = findDefaultLayoutParamsParameterName(function) != null

    /** Returns true when the property represents a Hikage layout value. */
    fun isHikagableProperty(property: KtProperty): Boolean {
        val file = property.containingKtFile
        return property.hasHikageAnalysisType() ||
            property.typeReference?.isHikageType(file) == true ||
            property.initializer?.isHikagableInitializer(file) == true
    }

    /** Returns true when the property is directly initialized by a Hikage factory call. */
    fun isDirectHikageFactoryProperty(property: KtProperty) = !property.hasDelegate() &&
        property.initializer?.isDirectHikageFactoryInitializer(property.containingKtFile) == true

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

    private fun KtCallExpression.isHikageFactoryCall(file: KtFile, callableIds: Set<CallableId>) =
        hasHikageFactoryResolvedCall(callableIds) || isHikageFactoryCallText(file, callableIds)

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