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
 * This file is created by fankes on 2026/7/16.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.model.HikageSymbols
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.uast.UArrayAccessExpression
import org.jetbrains.uast.toUElementOfType

/**
 * Suggests Hikage's typed ID accessors in place of array access followed by a cast.
 */
class HikageSafeTypeCastInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file !is KtFile) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
                super.visitBinaryWithTypeRHSExpression(expression)

                val replacement = expression.safeTypeCastReplacement() ?: return
                val target = expression.parent as? KtParenthesizedExpression ?: expression
                holder.registerProblem(
                    target,
                    "Can be replaced with safe type cast <code>${replacement.suggestion}</code>",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    ReplaceSafeTypeCastFix(target, replacement.expression)
                )
            }
        }
    }

    private fun KtBinaryExpressionWithTypeRHS.safeTypeCastReplacement(): SafeTypeCastReplacement? {
        val operation = operationReference.getReferencedNameElementType()
        if (operation != KtTokens.AS_KEYWORD && operation != KtTokens.AS_SAFE) return null
        val arrayAccess = left as? KtArrayAccessExpression ?: return null
        val receiverType = arrayAccess.toUElementOfType<UArrayAccessExpression>()
            ?.receiver
            ?.getExpressionType()
            ?.canonicalText
        if (receiverType != HikageSymbols.HIKAGE) return null

        val receiverText = arrayAccess.text
        val receiverName = receiverText.substringBefore("[")
        val receiverContent = receiverText.substringAfter("[").substringBefore("]")
        val castType = right?.text?.removeSuffix("?") ?: return null
        val isSafeCast = operation == KtTokens.AS_SAFE || text.endsWith("?")
        val functionName = if (isSafeCast) "getOrNull" else "get"

        return SafeTypeCastReplacement(
            expression = "$receiverName.$functionName<$castType>($receiverContent)",
            suggestion = "Hikage.$functionName&lt;$castType&gt;"
        )
    }

    private data class SafeTypeCastReplacement(
        val expression: String,
        val suggestion: String
    )

    private class ReplaceSafeTypeCastFix(
        target: PsiElement,
        private val replacement: String
    ) : LocalQuickFixOnPsiElement(target) {

        private val text = "Replace with '$replacement'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            (startElement as? KtExpression)?.replace(KtPsiFactory(project).createExpression(replacement))
        }
    }
}