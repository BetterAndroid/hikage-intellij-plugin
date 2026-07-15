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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.dsl.validation.PerformerValidator
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.highcapable.hikage.intellij.utils.ClassDetector
import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports Hikage performer function declarations that cannot produce generated code.
 */
class PerformerDeclarationInspection : LocalInspectionTool() {

    private companion object {

        const val VIEW_FIELD = "view"
        const val LPARAMS_FIELD = "lparams"
        const val ALIAS_FIELD = "alias"

        const val INVALID_VIEW_MESSAGE = "Performer declarations must target an <code>View</code> class other than <code>ViewGroup</code>"
        const val INVALID_ALIAS_MESSAGE = "Performer declaration's <code>alias</code> must be a valid Java/Kotlin identifier"
        const val INVALID_LAYOUT_PARAMS_MESSAGE = "Performer declaration's <code>lparams</code> must inherit from <code>ViewGroup.LayoutParams</code>"
        const val MISSING_CONSTRUCTOR_MESSAGE = "Performer declarations must have a constructor compatible with <code>(Context, AttributeSet?)</code>"
        const val NON_NULLABLE_ATTRIBUTE_SET_MESSAGE = "Performer declarations must declare the <code>AttributeSet</code> constructor parameter as nullable"
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR
        if (!ProjectService.getInstance(file.project).isHikageProject()) return PsiElementVisitor.EMPTY_VISITOR

        val validator = PerformerValidator.from(file.project)
        return object : KtVisitorVoid() {

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)

                classOrObject.annotationEntries.forEach { annotation ->
                    when {
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_ANNOTATION) -> {
                            holder.registerInvalidViewResult(
                                annotation.calleeExpression ?: annotation,
                                classOrObject.nameIdentifier ?: classOrObject,
                                validator.validate(PerformerValidator.Type.VIEW, classOrObject)
                            )
                            holder.registerInvalidAlias(annotation.attributeExpression(ALIAS_FIELD, 1))
                            holder.registerInvalidLayoutParams(
                                annotation.attributeExpression(LPARAMS_FIELD, 0),
                                validator
                            )
                        }
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION) -> {
                            val classLiteral = annotation.attributeExpression(VIEW_FIELD, 0) ?: return@forEach
                            holder.registerInvalidViewResult(
                                classLiteral,
                                classLiteral,
                                validator.validate(PerformerValidator.Type.VIEW, classLiteral)
                            )
                            holder.registerInvalidAlias(annotation.attributeExpression(ALIAS_FIELD, 2))
                            holder.registerInvalidLayoutParams(
                                annotation.attributeExpression(LPARAMS_FIELD, 1),
                                validator
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ProblemsHolder.registerInvalidViewResult(
        annotationTarget: PsiElement,
        viewTarget: PsiElement,
        result: PerformerValidator.Result
    ) {
        val (target, description) = when (result) {
            PerformerValidator.Result.VALID,
            PerformerValidator.Result.DEFAULT,
            PerformerValidator.Result.RESOLUTION_FAILED -> return
            PerformerValidator.Result.NOT_VIEW -> annotationTarget to INVALID_VIEW_MESSAGE
            PerformerValidator.Result.MISSING_CONSTRUCTOR -> viewTarget to MISSING_CONSTRUCTOR_MESSAGE
            PerformerValidator.Result.NON_NULLABLE_ATTRIBUTE_SET -> viewTarget to NON_NULLABLE_ATTRIBUTE_SET_MESSAGE
            PerformerValidator.Result.NOT_LAYOUT_PARAMS -> return
        }
        registerProblem(target, description, ProblemHighlightType.GENERIC_ERROR)
    }

    private fun ProblemsHolder.registerInvalidAlias(expression: KtExpression?) {
        val aliasExpression = expression ?: return
        val alias = aliasExpression.literalStringValue() ?: return
        if (alias.isNotEmpty() && !ClassDetector.verify(alias)) registerProblem(
            aliasExpression, INVALID_ALIAS_MESSAGE, ProblemHighlightType.GENERIC_ERROR
        )
    }

    private fun ProblemsHolder.registerInvalidLayoutParams(expression: KtExpression?, validator: PerformerValidator) {
        if (expression == null ||
            validator.validate(PerformerValidator.Type.LPARAMS, expression) != PerformerValidator.Result.NOT_LAYOUT_PARAMS
        ) return

        registerProblem(expression, INVALID_LAYOUT_PARAMS_MESSAGE, ProblemHighlightType.GENERIC_ERROR)
    }

    private fun KtAnnotationEntry.isHikageAnnotation(annotationFqName: String): Boolean {
        val referenceText = typeReference?.text ?: return false
        return referenceText == annotationFqName || containingKtFile.resolveClassName(referenceText) == annotationFqName
    }

    private fun KtAnnotationEntry.attributeExpression(name: String, positionalIndex: Int): KtExpression? {
        val argument = valueArguments.firstOrNull { argument ->
            argument.getArgumentName()?.asName?.identifier == name
        } ?: valueArguments.getOrNull(positionalIndex)
        return argument?.getArgumentExpression()
    }

    private fun KtExpression.literalStringValue() = (this as? KtStringTemplateExpression)
        ?.entries
        ?.takeIf { entries -> entries.all { it is KtLiteralStringTemplateEntry } }
        ?.joinToString(separator = "") { entry -> entry.text }
}