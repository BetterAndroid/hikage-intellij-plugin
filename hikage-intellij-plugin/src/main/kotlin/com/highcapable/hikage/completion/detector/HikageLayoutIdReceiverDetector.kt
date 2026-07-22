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
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.kavaref.extension.classOf
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Finds layout-ID string contexts and verifies their receiver through the shared layout resolver.
 */
internal object HikageLayoutIdReceiverDetector {

    /** Returns whether [offset] is inside a known layout-ID string. */
    fun isLayoutIdString(file: PsiFile, offset: Int): Boolean {
        val literal = sequenceOf(offset - 1, offset)
            .filter { candidate -> candidate >= 0 }
            .firstNotNullOfOrNull { candidate ->
                file.findElementAt(candidate)?.let { element ->
                    PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
                }
            }
            ?: return false
        if (!literal.isPlainString()) return false
        val receiver = literal.findLayoutIdReceiver() ?: return false

        return HikageLayoutResolver.from(file.project).resolve(receiver) != null
    }

    private fun KtStringTemplateExpression.isPlainString(): Boolean {
        val source = text
        return source.length >= 2 && source.startsWith('"') && source.endsWith('"') &&
            !source.startsWith("\"\"\"") && '$' !in source && '\\' !in source
    }

    private fun KtStringTemplateExpression.findLayoutIdReceiver() = findGetCallReceiver() ?: findArrayAccessReceiver()

    private fun KtStringTemplateExpression.findGetCallReceiver(): KtExpression? {
        val argument = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return null
        val call = generateSequence(argument.parent) { element -> element.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null
        if (call.calleeExpression?.text != HikageSymbols.HIKAGE_GET_FUNCTION_NAME ||
            call.valueArguments.firstOrNull() !== argument
        ) return null

        val qualified = call.parent as? KtQualifiedExpression ?: return null
        return qualified.receiverExpression.takeIf { qualified.selectorExpression === call }
    }

    private fun KtStringTemplateExpression.findArrayAccessReceiver(): KtExpression? {
        val arrayAccess = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtArrayAccessExpression>()
            .firstOrNull { access -> access.indexExpressions.singleOrNull() === this }
            ?: return null
        return arrayAccess.arrayExpression
    }
}
