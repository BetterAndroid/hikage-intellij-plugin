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

import com.highcapable.hikage.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.inspection.base.BaseInspectionTool
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.utils.extension.resolveClassName
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Reports functions that invoke Hikagable functions from an inherited performer scope without
 * declaring `@Hikagable`.
 */
class HikagablePropagationInspection : BaseInspectionTool() {

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file !is KtFile) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            private val reportedFunctions = hashSetOf<KtNamedFunction>()

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                if (expression.resolveMethod()?.let(DeclarationMatcher::isHikagableFunction) != true) return
                val function = expression.findPropagationTarget() ?: return
                if (!reportedFunctions.add(function)) return

                val functionName = function.name
                holder.registerProblem(
                    function.nameIdentifier ?: function.funKeyword ?: function,
                    functionName?.let {
                        "Function <code>$it</code> must be marked with the <code>@Hikagable</code> annotation"
                    } ?: "Function must be marked with the <code>@Hikagable</code> annotation",
                    ProblemHighlightType.GENERIC_ERROR,
                    AddHikagableAnnotationFix(function)
                )
            }
        }
    }

    private fun KtCallExpression.findPropagationTarget() = generateSequence(parent) { it.parent }
        .firstNotNullOfOrNull { current ->
            when (current) {
                is KtLambdaExpression -> when (current.scope()) {
                    LambdaScope.HIKAGE_PERFORMER,
                    LambdaScope.BLOCKING -> PropagationResult.Stop
                    LambdaScope.INLINE -> null
                }
                is KtNamedFunction -> if (DeclarationMatcher.isHikagableFunction(current))
                    PropagationResult.Stop
                else PropagationResult.Target(current)
                else -> null
            }
        }
        ?.let { result -> (result as? PropagationResult.Target)?.function }

    private fun KtLambdaExpression.scope(): LambdaScope {
        val argument = valueArgument()
        if (argument != null) {
            val ownerCall = argument.ownerCall() ?: return LambdaScope.BLOCKING
            val method = ownerCall.resolveMethod() ?: return LambdaScope.BLOCKING
            val parameter = argument.resolveParameter(ownerCall, method) ?: return LambdaScope.BLOCKING
            if (HikageSymbols.HIKAGE_PERFORMER in parameter.psi.type.canonicalText) return LambdaScope.HIKAGE_PERFORMER

            val function = method.navigationElement as? KtNamedFunction ?: return LambdaScope.BLOCKING
            val isInline = function.hasModifier(KtTokens.INLINE_KEYWORD)
            val prohibitsPropagation = parameter.source?.hasModifier(KtTokens.NOINLINE_KEYWORD) == true ||
                parameter.source?.hasModifier(KtTokens.CROSSINLINE_KEYWORD) == true
            return if (isInline && !prohibitsPropagation) LambdaScope.INLINE else LambdaScope.BLOCKING
        }

        return if (hasExplicitHikagePerformerType()) LambdaScope.HIKAGE_PERFORMER else LambdaScope.BLOCKING
    }

    private fun KtLambdaExpression.valueArgument() = generateSequence(parent) { it.parent }
        .takeWhile { it !is KtCallExpression && it !is KtNamedFunction }
        .filterIsInstance<KtValueArgument>()
        .firstOrNull()

    private fun KtValueArgument.ownerCall() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtValueArgument.resolveParameter(call: KtCallExpression, method: PsiMethod): LambdaParameter? {
        val function = method.navigationElement as? KtNamedFunction
        val argumentName = getArgumentName()?.asName?.identifier
        val sourceParameter = when {
            argumentName != null -> function?.valueParameters?.firstOrNull { it.name == argumentName }
            this is KtLambdaArgument -> function?.valueParameters?.lastOrNull()
            else -> call.valueArguments.indexOf(this)
                .takeIf { it >= 0 }
                ?.let { function?.valueParameters?.getOrNull(it) }
        }
        val psiParameter = sourceParameter?.name?.let { name ->
            method.parameterList.parameters.firstOrNull { it.name == name }
        } ?: when {
            argumentName != null -> method.parameterList.parameters.firstOrNull { it.name == argumentName }
            this is KtLambdaArgument -> method.parameterList.parameters.lastOrNull()
            else -> null
        } ?: return null

        return LambdaParameter(psiParameter, sourceParameter ?: psiParameter.navigationElement as? KtParameter)
    }

    private fun KtLambdaExpression.hasExplicitHikagePerformerType(): Boolean {
        val owner = generateSequence(parent) { it.parent }.firstOrNull {
            it is KtProperty || it is KtParameter || it is KtNamedFunction || it is KtLambdaExpression
        }
        val typeReference = when (owner) {
            is KtProperty -> owner.typeReference
            is KtParameter -> owner.typeReference
            is KtNamedFunction -> owner.typeReference
            else -> null
        }
        return typeReference?.isHikagePerformerType() == true
    }

    private fun KtTypeReference.isHikagePerformerType(): Boolean {
        val typeName = text.substringBefore('<').removeSuffix("?").trim()
        return typeName == HikageSymbols.HIKAGE_PERFORMER_LAMBDA ||
            containingKtFile.resolveClassName(typeName) == HikageSymbols.HIKAGE_PERFORMER_LAMBDA
    }

    private fun KtFile.addHikagableImport(project: Project) {
        if (importDirectives.any { it.importedFqName?.asString() == HikageSymbols.HIKAGABLE_ANNOTATION }) return
        val importDirective = KtPsiFactory(project).createImportDirective(ImportPath(FqName(HikageSymbols.HIKAGABLE_ANNOTATION), false))
        importList?.add(importDirective) ?: addAfter(importDirective, packageDirective)
    }

    private enum class LambdaScope {
        HIKAGE_PERFORMER,
        INLINE,
        BLOCKING
    }

    private data class LambdaParameter(
        val psi: PsiParameter,
        val source: KtParameter?
    )

    private sealed interface PropagationResult {
        data object Stop : PropagationResult
        data class Target(val function: KtNamedFunction) : PropagationResult
    }

    private inner class AddHikagableAnnotationFix(function: KtNamedFunction) : LocalQuickFixOnPsiElement(function) {

        private val text = function.name?.let { "Add '@Hikagable' to '$it'" } ?: "Add '@Hikagable'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val function = startElement as? KtNamedFunction ?: return
            function.addAnnotationEntry(KtPsiFactory(project).createAnnotationEntry("@Hikagable"))
            (file as? KtFile)?.addHikagableImport(project)
        }
    }
}