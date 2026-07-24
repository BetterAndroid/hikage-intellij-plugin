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
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.toUElementOfType

/**
 * Reports Android resource calls that escape the current Hikage performer resource scope.
 */
class ResourcesScopeInspection : BaseInspectionTool() {

    private companion object {

        const val BETTERANDROID_RESOURCES_UTILS_CLASS = "com.highcapable.betterandroid.ui.extension.component.base.ResourcesUtils"

        const val STRING_RESOURCE_FUNCTION = "stringResource"
        const val PLURAL_STRING_RESOURCE_FUNCTION = "pluralStringResource"
        const val PLURAL_TEXT_RESOURCE_FUNCTION = "pluralTextResource"
        const val TEXT_RESOURCE_FUNCTION = "textResource"
        const val STRING_ARRAY_RESOURCE_FUNCTION = "stringArrayResource"
        const val INTEGER_RESOURCE_FUNCTION = "integerResource"
        const val INTEGER_ARRAY_RESOURCE_FUNCTION = "integerArrayResource"
        const val BOOLEAN_RESOURCE_FUNCTION = "booleanResource"
        const val COLOR_RESOURCE_FUNCTION = "colorResource"
        const val STATE_COLOR_RESOURCE_FUNCTION = "stateColorResource"
        const val DRAWABLE_RESOURCE_FUNCTION = "drawableResource"
        const val DIMEN_RESOURCE_FUNCTION = "dimenResource"
        const val DIMEN_PIXEL_SIZE_RESOURCE_FUNCTION = "dimenPixelSizeResource"
        const val DIMEN_PIXEL_OFFSET_RESOURCE_FUNCTION = "dimenPixelOffsetResource"
        const val FRACTION_RESOURCE_FUNCTION = "fractionResource"
        const val FONT_RESOURCE_FUNCTION = "fontResource"

        val RESOURCE_METHOD_NAMES = setOf(
            "getString",
            "getQuantityString",
            "getQuantityText",
            "getText",
            "getStringArray",
            "getInteger",
            "getIntArray",
            "getBoolean",
            "getColor",
            "getColorStateList",
            "getDrawable",
            "getDimension",
            "getDimensionPixelSize",
            "getDimensionPixelOffset",
            "getFraction",
            "getFont",
            "getColorCompat",
            "getColorStateListCompat",
            "getDrawableCompat",
            "getDrawableCompatTyped",
            "getFontCompat"
        )
    }

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            private val reportedResources = hashSetOf<ReportedResource>()

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                val call = expression.toUElementOfType<UCallExpression>() ?: return
                val method = call.resolve() ?: return
                val replacement = method.resourceReplacement(file.project) ?: return
                if (!call.isInsideHikagePerformerScope(expression)) return

                val target = call.fullCallSourcePsi() ?: expression
                val report = target.toReportedResource(replacement)
                if (!reportedResources.add(report)) return
                val message = "Use <code>${replacement.functionName}</code> to access resources from the current performer scope"
                val fix = call.createReplacementFix(target, replacement)
                if (fix == null) holder.registerProblem(
                    target,
                    message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                ) else holder.registerProblem(
                    target,
                    message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fix
                )
            }
        }
    }

    private fun PsiMethod.resourceReplacement(project: Project): ResourceReplacement? {
        if (name !in RESOURCE_METHOD_NAMES) return null
        val className = containingClass?.qualifiedName ?: return null

        return when {
            className == AndroidSymbols.CONTEXT_COMPAT_CLASS -> when (name) {
                "getColor" -> ResourceReplacement(COLOR_RESOURCE_FUNCTION, 1)
                "getColorStateList" -> ResourceReplacement(STATE_COLOR_RESOURCE_FUNCTION, 1)
                "getDrawable" -> ResourceReplacement(DRAWABLE_RESOURCE_FUNCTION, 1)
                else -> null
            }
            className == AndroidSymbols.RESOURCES_COMPAT_CLASS -> when (name) {
                "getColor" -> ResourceReplacement(COLOR_RESOURCE_FUNCTION, 1)
                "getColorStateList" -> ResourceReplacement(STATE_COLOR_RESOURCE_FUNCTION, 1)
                "getDrawable" -> ResourceReplacement(DRAWABLE_RESOURCE_FUNCTION, 1)
                "getFont" -> ResourceReplacement(FONT_RESOURCE_FUNCTION, 1)
                else -> null
            }
            className == BETTERANDROID_RESOURCES_UTILS_CLASS -> when (name) {
                "getColorCompat" -> ResourceReplacement(COLOR_RESOURCE_FUNCTION)
                "getColorStateListCompat" -> ResourceReplacement(STATE_COLOR_RESOURCE_FUNCTION)
                "getDrawableCompat", "getDrawableCompatTyped" -> ResourceReplacement(DRAWABLE_RESOURCE_FUNCTION)
                "getFontCompat" -> ResourceReplacement(FONT_RESOURCE_FUNCTION)
                else -> null
            }
            extendsClass(project, AndroidSymbols.CONTEXT_CLASS) -> when (name) {
                "getString" -> ResourceReplacement(STRING_RESOURCE_FUNCTION, keepRemainingArguments = true)
                "getText" -> ResourceReplacement(TEXT_RESOURCE_FUNCTION)
                "getColor" -> ResourceReplacement(COLOR_RESOURCE_FUNCTION)
                "getColorStateList" -> ResourceReplacement(STATE_COLOR_RESOURCE_FUNCTION)
                "getDrawable" -> ResourceReplacement(DRAWABLE_RESOURCE_FUNCTION)
                "getFont" -> ResourceReplacement(FONT_RESOURCE_FUNCTION)
                else -> null
            }
            extendsClass(project, AndroidSymbols.RESOURCES_CLASS) -> when (name) {
                "getString" -> ResourceReplacement(STRING_RESOURCE_FUNCTION, keepRemainingArguments = true)
                "getQuantityString" -> ResourceReplacement(PLURAL_STRING_RESOURCE_FUNCTION, keepRemainingArguments = true)
                "getQuantityText" -> ResourceReplacement(PLURAL_TEXT_RESOURCE_FUNCTION, keepRemainingArguments = true)
                "getText" -> ResourceReplacement(TEXT_RESOURCE_FUNCTION)
                "getStringArray" -> ResourceReplacement(STRING_ARRAY_RESOURCE_FUNCTION)
                "getInteger" -> ResourceReplacement(INTEGER_RESOURCE_FUNCTION)
                "getIntArray" -> ResourceReplacement(INTEGER_ARRAY_RESOURCE_FUNCTION)
                "getBoolean" -> ResourceReplacement(BOOLEAN_RESOURCE_FUNCTION)
                "getColor" -> ResourceReplacement(COLOR_RESOURCE_FUNCTION)
                "getColorStateList" -> ResourceReplacement(STATE_COLOR_RESOURCE_FUNCTION)
                "getDrawable" -> ResourceReplacement(DRAWABLE_RESOURCE_FUNCTION)
                "getDimension" -> ResourceReplacement(DIMEN_RESOURCE_FUNCTION)
                "getDimensionPixelSize" -> ResourceReplacement(DIMEN_PIXEL_SIZE_RESOURCE_FUNCTION)
                "getDimensionPixelOffset" -> ResourceReplacement(DIMEN_PIXEL_OFFSET_RESOURCE_FUNCTION)
                "getFraction" -> ResourceReplacement(FRACTION_RESOURCE_FUNCTION, keepRemainingArguments = true)
                "getFont" -> ResourceReplacement(FONT_RESOURCE_FUNCTION)
                else -> null
            }
            else -> null
        }
    }

    private fun PsiMethod.extendsClass(project: Project, className: String): Boolean {
        val clazz = containingClass ?: return false
        val target = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project)) ?: return false

        return clazz == target || clazz.isInheritor(target, true)
    }

    private fun UCallExpression.isInsideHikagePerformerScope(expression: KtCallExpression) = generateSequence(uastParent) { it.uastParent }
        .filterIsInstance<UMethod>()
        .any { it.javaPsi.isHikagePerformerFunction() } ||
        generateSequence(expression.parent) { it.parent }
            .filterIsInstance<KtLambdaExpression>()
            .any { it.isHikagePerformerArgument() }

    private fun PsiMethod.isHikagePerformerFunction() =
        DeclarationMatcher.isHikagableFunction(this) && parameterList.parameters.any { it.type.isHikagePerformerType() }

    private fun KtLambdaExpression.isHikagePerformerArgument(): Boolean {
        val argument = valueArgument() ?: return false
        val call = argument.ownerCall() ?: return false
        val method = call.resolveMethod() ?: return false

        return call.findParameter(argument, method)?.type?.isHikagePerformerType() == true
    }

    private fun KtLambdaExpression.valueArgument() = generateSequence(parent) { it.parent }
        .takeWhile { it !is KtCallExpression }
        .filterIsInstance<KtValueArgument>()
        .firstOrNull()

    private fun KtValueArgument.ownerCall() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtCallExpression.findParameter(argument: KtValueArgument, method: PsiMethod): PsiParameter? {
        val parameters = method.parameterList.parameters
        argument.getArgumentName()?.asName?.identifier?.let { name ->
            return parameters.firstOrNull { it.name == name }
        }
        if (argument is KtLambdaArgument) return parameters.lastOrNull()

        return valueArguments.indexOf(argument).takeIf { it >= 0 }?.let(parameters::getOrNull)
    }

    private fun PsiType.isHikagePerformerType() = canonicalText.contains(HikageSymbols.HIKAGE_PERFORMER)

    private fun UCallExpression.fullCallSourcePsi() = when (val parent = uastParent) {
        is UQualifiedReferenceExpression -> if (parent.selector == this) parent.sourcePsi else sourcePsi
        else -> sourcePsi
    }

    private fun UCallExpression.createReplacementFix(
        target: PsiElement,
        replacement: ResourceReplacement
    ): ReplaceResourceCallFix? {
        val replacementArguments = if (replacement.keepRemainingArguments)
            valueArguments.drop(replacement.resourceArgumentIndex)
        else valueArguments.getOrNull(replacement.resourceArgumentIndex)?.let(::listOf).orEmpty()
        if (replacementArguments.isEmpty()) return null
        val replacementText = replacementArguments.joinToString { it.asSourceString() }

        return ReplaceResourceCallFix(
            target,
            replacement.functionName,
            "${replacement.functionName}($replacementText)"
        )
    }

    private fun PsiElement.toReportedResource(replacement: ResourceReplacement) = ReportedResource(
        filePath = containingFile?.virtualFile?.path,
        startOffset = textRange.startOffset,
        endOffset = textRange.endOffset,
        functionName = replacement.functionName
    )

    private data class ResourceReplacement(
        val functionName: String,
        val resourceArgumentIndex: Int = 0,
        val keepRemainingArguments: Boolean = false
    )

    private data class ReportedResource(
        val filePath: String?,
        val startOffset: Int,
        val endOffset: Int,
        val functionName: String
    )

    private class ReplaceResourceCallFix(
        target: PsiElement,
        functionName: String,
        private val replacement: String
    ) : LocalQuickFixOnPsiElement(target) {

        private val text = "Replace with '$functionName'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            (startElement as? KtExpression)?.replace(KtPsiFactory(project).createExpression(replacement))
        }
    }
}