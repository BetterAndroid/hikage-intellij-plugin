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

import com.highcapable.hikage.intellij.dsl.validation.ViewConstructorValidator
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports Hikage view declarations that cannot produce generated performer functions.
 */
class HikageViewDeclarationInspection : LocalInspectionTool() {

    private companion object {
        const val VIEW_FIELD = "view"
        const val INVALID_VIEW_MESSAGE = "Hikage view declarations must target an View class other than ViewGroup"
        const val MISSING_CONSTRUCTOR_MESSAGE = "Hikage view declarations must have a constructor compatible with (Context, AttributeSet?)"
        const val NON_NULLABLE_ATTRIBUTE_SET_MESSAGE = "Hikage view declarations must declare the AttributeSet constructor parameter as nullable"
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR
        if (!ProjectService.getInstance(file.project).isHikageProject()) return PsiElementVisitor.EMPTY_VISITOR

        val validator = ViewConstructorValidator(file.project)
        return object : KtVisitorVoid() {

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)
                classOrObject.annotationEntries.forEach { annotation ->
                    when {
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_ANNOTATION) ->
                            holder.registerInvalidResult(annotation, validator.validate(classOrObject))
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION) -> {
                            val classLiteral = annotation.attributeExpression(VIEW_FIELD) ?: return@forEach
                            holder.registerInvalidResult(annotation, validator.validate(classLiteral))
                        }
                    }
                }
            }
        }
    }

    private fun ProblemsHolder.registerInvalidResult(annotation: KtAnnotationEntry, result: ViewConstructorValidator.Result) {
        val description = when (result) {
            ViewConstructorValidator.Result.VALID,
            ViewConstructorValidator.Result.RESOLUTION_FAILED -> return
            ViewConstructorValidator.Result.NOT_VIEW -> INVALID_VIEW_MESSAGE
            ViewConstructorValidator.Result.MISSING_CONSTRUCTOR -> MISSING_CONSTRUCTOR_MESSAGE
            ViewConstructorValidator.Result.NON_NULLABLE_ATTRIBUTE_SET -> NON_NULLABLE_ATTRIBUTE_SET_MESSAGE
        }
        registerProblem(
            annotation.calleeExpression ?: annotation,
            description,
            ProblemHighlightType.GENERIC_ERROR
        )
    }

    private fun KtAnnotationEntry.isHikageAnnotation(annotationFqName: String): Boolean {
        val referenceText = typeReference?.text ?: return false
        return referenceText == annotationFqName || containingKtFile.resolveClassName(referenceText) == annotationFqName
    }

    private fun KtAnnotationEntry.attributeExpression(name: String): KtExpression? {
        val argument = valueArguments.firstOrNull { argument ->
            argument.getArgumentName()?.asName?.identifier == name
        } ?: valueArguments.getOrNull(if (name == VIEW_FIELD) 0 else -1)
        return argument?.getArgumentExpression()
    }
}