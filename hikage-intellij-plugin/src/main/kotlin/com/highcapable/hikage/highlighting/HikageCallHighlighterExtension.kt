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
package com.highcapable.hikage.highlighting

import com.highcapable.hikage.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.XmlHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.highlighting.KotlinCallHighlighterExtension
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Highlights Hikage DSL calls in the same call-highlighting pass used by Kotlin K2.
 */
class HikageCallHighlighterExtension : KotlinCallHighlighterExtension {

    private companion object {

        const val HIKAGABLE_CALL_TEXT_ATTRIBUTES_NAME = "HikagableCallTextAttributes"
        const val LAYOUT_PARAMS_CALL_TEXT_ATTRIBUTES_NAME = "HikageLayoutParamsCallTextAttributes"
        const val RESOURCES_SCOPE_CALL_TEXT_ATTRIBUTES_NAME = "HikageResourcesScopeCallTextAttributes"
        const val HIKAGE_ATTRIBUTE_SET_CALL_TEXT_ATTRIBUTES_NAME = "HikageAttributeSetCallTextAttributes"

        val HIKAGABLE_CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            HIKAGABLE_CALL_TEXT_ATTRIBUTES_NAME,
            DefaultLanguageHighlighterColors.FUNCTION_CALL
        )

        val LAYOUT_PARAMS_CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            LAYOUT_PARAMS_CALL_TEXT_ATTRIBUTES_NAME,
            DefaultLanguageHighlighterColors.FUNCTION_CALL
        )

        val RESOURCES_SCOPE_CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            RESOURCES_SCOPE_CALL_TEXT_ATTRIBUTES_NAME,
            DefaultLanguageHighlighterColors.FUNCTION_CALL
        )

        val HIKAGE_ATTRIBUTE_SET_CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            HIKAGE_ATTRIBUTE_SET_CALL_TEXT_ATTRIBUTES_NAME,
            DefaultLanguageHighlighterColors.FUNCTION_CALL
        )

        val HIKAGABLE_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            HIKAGABLE_CALL_TEXT_ATTRIBUTES_KEY
        )

        val LAYOUT_PARAMS_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            LAYOUT_PARAMS_CALL_TEXT_ATTRIBUTES_KEY
        )

        val RESOURCES_SCOPE_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            RESOURCES_SCOPE_CALL_TEXT_ATTRIBUTES_KEY
        )

        val HIKAGE_ATTRIBUTE_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            XmlHighlighterColors.XML_NS_PREFIX
        )

        val HIKAGE_ATTRIBUTE_SET_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            HIKAGE_ATTRIBUTE_SET_CALL_TEXT_ATTRIBUTES_KEY
        )
    }

    override fun KaSession.highlightCall(elementToHighlight: PsiElement, call: KaCall): HighlightInfoType? {
        if (!ProjectGate.from(elementToHighlight.project).isEnabled()) return null

        val memberCall = call as? KaCallableMemberCall<*, *> ?: return null
        val scopeElement = PsiTreeUtil.getParentOfType(elementToHighlight, classOf<KtElement>(), false) ?: return null
        return highlightInfoType(memberCall, scopeElement)
    }

    private fun KaSession.highlightInfoType(
        call: KaCallableMemberCall<*, *>,
        scopeElement: KtElement
    ) = when (val symbol = call.symbol) {
        is KaNamedFunctionSymbol -> when {
            DeclarationMatcher.isHikageLayoutParamsFunction(symbol) -> LAYOUT_PARAMS_CALL_TEXT_TYPE
            DeclarationMatcher.isHikageResourcesScopeFunction(symbol) -> RESOURCES_SCOPE_CALL_TEXT_TYPE
            DeclarationMatcher.isHikageAttributeFunction(symbol) -> HIKAGE_ATTRIBUTE_CALL_TEXT_TYPE
            DeclarationMatcher.isHikageAttributeSetFunction(symbol) -> HIKAGE_ATTRIBUTE_SET_CALL_TEXT_TYPE
            DeclarationMatcher.isHikagableFunction(symbol) || symbol.isHikageInvokeOperatorCall(call) ->
                if (DeclarationMatcher.isInHikagePerformerScope(this, scopeElement, includeOuterReceivers = true))
                    HIKAGABLE_CALL_TEXT_TYPE
                else null
            else -> null
        }
        is KaPropertySymbol -> when {
            DeclarationMatcher.isHikageAttributeFunction(symbol) -> HIKAGE_ATTRIBUTE_CALL_TEXT_TYPE
            symbol.isDirectHikageFactoryProperty() ->
                if (DeclarationMatcher.isInHikagePerformerScope(this, scopeElement, includeOuterReceivers = true))
                    HIKAGABLE_CALL_TEXT_TYPE
                else null
            else -> null
        }
        else -> null
    }

    private fun KaNamedFunctionSymbol.isHikageInvokeOperatorCall(call: KaCallableMemberCall<*, *>): Boolean {
        if (!isOperator || name != OperatorNameConventions.INVOKE) return false
        val receiverType = call.partiallyAppliedSymbol.dispatchReceiver?.type ?: return false

        return receiverType.isHikageType()
    }

    private fun KaPropertySymbol.isDirectHikageFactoryProperty() =
        (psi as? KtProperty)?.let(DeclarationMatcher::isDirectHikageFactoryProperty) == true

    private fun KaType.isHikageType() = (this as? KaClassType)?.classId.let { classId ->
        classId == HikageSymbols.HIKAGE_CLASS_ID || classId == HikageSymbols.HIKAGE_DELEGATE_CLASS_ID
    }
}