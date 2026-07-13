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
package com.highcapable.hikage.intellij.highlighting

import com.highcapable.hikage.intellij.inspection.DeclarationMatcher
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.highlighting.KotlinCallHighlighterExtension
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Highlights calls to `Hikagable` declarations in the same call-highlighting pass used by Kotlin K2.
 */
class HikagableFunctionCallHighlighterExtension : KotlinCallHighlighterExtension {

    private companion object {

        const val CALL_TEXT_ATTRIBUTES_NAME = "HikagableCallTextAttributes"

        val CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            CALL_TEXT_ATTRIBUTES_NAME,
            DefaultLanguageHighlighterColors.FUNCTION_CALL
        )

        val CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            CALL_TEXT_ATTRIBUTES_KEY
        )
    }

    override fun KaSession.highlightCall(elementToHighlight: PsiElement, call: KaCall): HighlightInfoType? {
        val memberCall = call as? KaCallableMemberCall<*, *> ?: return null
        if (!ProjectService.getInstance(elementToHighlight.project).isHikageProject()) return null
        if (!memberCall.isHikageInvocation()) return null

        return CALL_TEXT_TYPE
    }

    private fun KaCallableMemberCall<*, *>.isHikageInvocation() = when (val symbol = symbol) {
        is KaNamedFunctionSymbol -> symbol.hasHikagableAnnotation() || symbol.isHikageInvokeOperatorCall(this)
        is KaPropertySymbol -> symbol.isDirectHikageFactoryProperty()
        else -> false
    }

    private fun KaNamedFunctionSymbol.isHikageInvokeOperatorCall(call: KaCallableMemberCall<*, *>): Boolean {
        if (!isOperator || name != OperatorNameConventions.INVOKE) return false
        val receiverType = call.partiallyAppliedSymbol.dispatchReceiver?.type ?: return false
        return receiverType.isHikageType()
    }

    private fun KaAnnotated.hasHikagableAnnotation() = annotations.contains(HikageSymbols.HIKAGABLE_ANNOTATION_CLASS_ID)

    private fun KaPropertySymbol.isDirectHikageFactoryProperty() =
        (psi as? KtProperty)?.let(DeclarationMatcher::isDirectHikageFactoryProperty) == true

    private fun KaType.isHikageType() = (this as? KaClassType)?.classId.let { classId ->
        classId == HikageSymbols.HIKAGE_CLASS_ID || classId == HikageSymbols.HIKAGE_DELEGATE_CLASS_ID
    }
}