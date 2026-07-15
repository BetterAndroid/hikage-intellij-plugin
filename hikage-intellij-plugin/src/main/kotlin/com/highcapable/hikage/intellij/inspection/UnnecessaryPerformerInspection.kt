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
 * This file is created by fankes on 2026/7/15.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.dsl.detector.ViewTypeDetector
import com.highcapable.hikage.intellij.dsl.extension.isHikageAnnotation
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.highcapable.hikage.intellij.utils.extension.attributeArgument
import com.highcapable.hikage.intellij.utils.extension.attributeExpression
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports explicit performer parameters that cannot affect generated code.
 */
class UnnecessaryPerformerInspection : LocalInspectionTool() {

    private companion object {

        const val VIEW_FIELD = "view"
        const val PERFORMER_FIELD = "performer"

        const val MESSAGE = "The <code>performer</code> parameter is unnecessary because the target <code>View</code> is not a <code>ViewGroup</code>"
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR
        if (!ProjectService.getInstance(file.project).isHikageProject()) return PsiElementVisitor.EMPTY_VISITOR

        val viewTypeDetector = ViewTypeDetector.from(file.project)

        return object : KtVisitorVoid() {

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)

                classOrObject.annotationEntries.forEach { annotation ->
                    when {
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_ANNOTATION) -> {
                            val isView = viewTypeDetector.isView(classOrObject)
                            holder.registerIfUnnecessary(
                                annotation.attributeArgument(PERFORMER_FIELD, 4),
                                isView,
                                isView && viewTypeDetector.isViewGroup(classOrObject)
                            )
                        }
                        annotation.isHikageAnnotation(HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION) -> {
                            val classLiteral = annotation.attributeExpression(VIEW_FIELD, 0) ?: return@forEach
                            val isView = viewTypeDetector.isView(classLiteral)
                            holder.registerIfUnnecessary(
                                annotation.attributeArgument(PERFORMER_FIELD, 5),
                                isView,
                                isView && viewTypeDetector.isViewGroup(classLiteral)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ProblemsHolder.registerIfUnnecessary(argument: KtValueArgument?, isView: Boolean, isViewGroup: Boolean) {
        if (argument == null || !isView || isViewGroup) return

        registerProblem(
            argument,
            MESSAGE,
            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
            RemovePerformerArgumentFix(argument)
        )
    }

    private class RemovePerformerArgumentFix(argument: KtValueArgument) : LocalQuickFixOnPsiElement(argument) {

        private companion object {
            const val TEXT = "Remove 'performer'"
        }

        override fun getFamilyName() = TEXT
        override fun getText() = TEXT

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val argument = startElement as? KtValueArgument ?: return
            (argument.parent as? KtValueArgumentList)?.removeArgument(argument)
        }
    }
}