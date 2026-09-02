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
package com.highcapable.hikage.inspection

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.inspection.base.BaseInspectionTool
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.symbol.SystemSymbols
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports generated performers passed to DSL blocks that are not `Hikage.Performer` scopes.
 */
class HikagableBeyondScopeInspection : BaseInspectionTool() {

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : KtVisitorVoid() {

        private val reportedCalls = hashSetOf<KtCallExpression>()

        override fun visitCallExpression(expression: KtCallExpression) {
            super.visitCallExpression(expression)

            val method = expression.resolveMethod() ?: return
            if (!DeclarationMatcher.isHikagableFunction(method) &&
                !DeclarationMatcher.isHikageLayoutParamsFunction(method)
            ) return

            holder.reportBeyondScope(expression, method, reportedCalls)
        }
    }

    private fun ProblemsHolder.reportBeyondScope(
        call: KtCallExpression,
        method: PsiMethod,
        reportedCalls: MutableSet<KtCallExpression>
    ) {
        call.invalidDslLambdas(method).forEach { lambda ->
            lambda.bodyExpression?.children?.filterIsInstance<KtCallExpression>()?.forEach { nestedCall ->
                val nestedMethod = nestedCall.resolveMethod() ?: return@forEach
                if (!DeclarationMatcher.isHikagableFunction(nestedMethod)) return@forEach

                if (reportedCalls.add(nestedCall)) registerProblem(
                    nestedCall,
                    "Performers are not allowed to appear in <code>${method.name}</code> DSL creation process",
                    ProblemHighlightType.GENERIC_ERROR,
                    DeleteCallExpressionFix(nestedCall)
                )
                reportBeyondScope(nestedCall, nestedMethod, reportedCalls)
            }
        }
    }

    private fun KtCallExpression.invalidDslLambdas(method: PsiMethod) = buildList {
        lambdaArguments.lastOrNull()?.let(::add)
        addAll(valueArguments)
    }.mapNotNull { argument -> argument.toInvalidDslLambda(method) }

    private fun KtValueArgument.toInvalidDslLambda(method: PsiMethod): KtLambdaExpression? {
        val parameter = getArgumentName()?.asName?.identifier?.let { name ->
            method.parameterList.parameters.firstOrNull { it.name == name }
        } ?: takeIf { this is KtLambdaArgument }?.let { method.parameterList.parameters.lastOrNull() }
        if (parameter?.type?.isNonPerformerDslLambda() != true) return null

        return getArgumentExpression() as? KtLambdaExpression
    }

    private fun PsiType.isNonPerformerDslLambda(): Boolean {
        val functionType = this as? PsiClassType ?: return false
        if (functionType.canonicalClassName() != SystemSymbols.KOTLIN_FUNCTION1) return false

        val typeArguments = functionType.parameters
        return !(typeArguments.size != 2 || typeArguments.last().canonicalClassName() != SystemSymbols.KOTLIN_UNIT) &&
            typeArguments.first().canonicalClassName() != HikageSymbols.HIKAGE_PERFORMER
    }

    private class DeleteCallExpressionFix(call: KtCallExpression) : LocalQuickFixOnPsiElement(call) {

        companion object {
            const val DELETE_CALL_EXPRESSION = "Delete Call Expression"
        }

        override fun getFamilyName() = DELETE_CALL_EXPRESSION
        override fun getText() = DELETE_CALL_EXPRESSION

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            (startElement as? KtCallExpression)?.delete()
        }
    }
}