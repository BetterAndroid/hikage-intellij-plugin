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
package com.highcapable.hikage.inspection

import com.highcapable.hikage.analysis.AndroidViewTypeResolver
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.dsl.model.HikageViewAnnotation
import com.highcapable.hikage.dsl.resolver.AnnotationValueResolver
import com.highcapable.hikage.dsl.resolver.PerformerDeclarationCollector
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.dsl.validation.PerformerValidator
import com.highcapable.hikage.inspection.base.BaseInspectionTool
import com.highcapable.hikage.utils.ClassNameValidator
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
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports Hikage performer function declarations that cannot produce generated code.
 */
class PerformerDeclarationInspection : BaseInspectionTool() {

    private companion object {
        const val INVALID_VIEW_MESSAGE = "Performer declarations must target an <code>View</code> class other than <code>ViewGroup</code>"
        const val INVALID_ALIAS_MESSAGE = "Performer declaration's <code>alias</code> must be a valid Java/Kotlin identifier"
        const val INVALID_LAYOUT_PARAMS_MESSAGE = "Performer declaration's <code>lparams</code> must inherit from <code>ViewGroup.LayoutParams</code>"
        const val MISSING_CONSTRUCTOR_MESSAGE = "Performer declarations must have a constructor compatible with <code>(Context, AttributeSet?)</code>"
        const val NON_NULLABLE_ATTRIBUTE_SET_MESSAGE = "Performer declarations must declare the <code>AttributeSet</code> constructor parameter as nullable"
        const val INVALID_DECLARATION_OBJECT_MESSAGE = "<code>@HikageViewDeclaration</code> must be declared on an independent <code>object</code>"
        const val DUPLICATE_VIEW_DECLARATION_MESSAGE = "A <code>View</code> may be declared by only one <code>@HikageView</code>, " +
            "<code>@HikageViewDeclaration</code>, or view declaration file"
    }

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR

        val validator = PerformerValidator.from(file.project)
        val annotationValues = AnnotationValueResolver.from(file.project)
        val viewTypeResolver = AndroidViewTypeResolver.from(file.project)
        val collector = PerformerDeclarationCollector.from(file.project)
        val duplicateViewClasses = PerformerDeclarations.duplicateViewClasses(file.project)

        return object : KtVisitorVoid() {

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)

                classOrObject.annotationEntries.forEach { annotation ->
                    when {
                        DeclarationMatcher.isHikageAnnotation(annotation, HikageViewAnnotation.View.fqName) -> {
                            holder.registerDuplicateViewDeclaration(
                                annotation,
                                collector.annotationViewClass(classOrObject, annotation),
                                duplicateViewClasses
                            )
                            val viewResult = validator.validate(PerformerValidator.Type.VIEW, classOrObject)
                            holder.registerInvalidViewResult(
                                annotation.calleeExpression ?: annotation,
                                classOrObject.nameIdentifier ?: classOrObject,
                                viewResult
                            )
                            holder.registerInvalidAlias(
                                HikageViewAnnotation.View.alias.expression(annotation),
                                annotationValues.string(annotation, HikageViewAnnotation.View.alias)
                            )
                            holder.registerInvalidLayoutParams(
                                HikageViewAnnotation.View.lparams.expression(annotation),
                                validator,
                                viewTypeResolver.isViewGroup(classOrObject)
                            )
                        }
                        DeclarationMatcher.isHikageAnnotation(annotation, HikageViewAnnotation.Declaration.fqName) -> {
                            holder.registerDuplicateViewDeclaration(
                                annotation,
                                collector.annotationViewClass(classOrObject, annotation),
                                duplicateViewClasses
                            )
                            holder.registerInvalidDeclarationObject(annotation, classOrObject)
                            val classLiteral = requireNotNull(HikageViewAnnotation.Declaration.view).expression(annotation)
                                ?: return@forEach
                            val viewResult = validator.validate(PerformerValidator.Type.VIEW, classLiteral)
                            holder.registerInvalidViewResult(
                                classLiteral,
                                classLiteral,
                                viewResult
                            )
                            holder.registerInvalidAlias(
                                HikageViewAnnotation.Declaration.alias.expression(annotation),
                                annotationValues.string(annotation, HikageViewAnnotation.Declaration.alias)
                            )
                            holder.registerInvalidLayoutParams(
                                HikageViewAnnotation.Declaration.lparams.expression(annotation),
                                validator,
                                viewTypeResolver.isViewGroup(classLiteral)
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

    private fun ProblemsHolder.registerInvalidAlias(expression: KtExpression?, value: String? = expression?.literalStringValue()) {
        val aliasExpression = expression ?: return
        if (!value.isNullOrEmpty() && !ClassNameValidator.check(value)) registerProblem(
            aliasExpression, INVALID_ALIAS_MESSAGE, ProblemHighlightType.GENERIC_ERROR
        )
    }

    private fun ProblemsHolder.registerInvalidLayoutParams(
        expression: KtExpression?,
        validator: PerformerValidator,
        isViewGroup: Boolean
    ) {
        if (!isViewGroup || expression == null ||
            validator.validate(PerformerValidator.Type.LPARAMS, expression) != PerformerValidator.Result.NOT_LAYOUT_PARAMS
        ) return

        registerProblem(expression, INVALID_LAYOUT_PARAMS_MESSAGE, ProblemHighlightType.GENERIC_ERROR)
    }

    private fun ProblemsHolder.registerDuplicateViewDeclaration(annotation: PsiElement, viewClass: String?, duplicateViewClasses: Set<String>) {
        if (viewClass !in duplicateViewClasses) return
        registerProblem(annotation, DUPLICATE_VIEW_DECLARATION_MESSAGE, ProblemHighlightType.GENERIC_ERROR)
    }

    private fun ProblemsHolder.registerInvalidDeclarationObject(annotation: KtAnnotationEntry, declaration: KtClassOrObject) {
        if (declaration is KtObjectDeclaration && !declaration.isCompanion()) return
        registerProblem(annotation, INVALID_DECLARATION_OBJECT_MESSAGE, ProblemHighlightType.GENERIC_ERROR)
    }

    private fun KtExpression.literalStringValue() = (this as? KtStringTemplateExpression)
        ?.entries
        ?.takeIf { entries -> entries.all { it is KtLiteralStringTemplateEntry } }
        ?.joinToString(separator = "") { entry -> entry.text }
}