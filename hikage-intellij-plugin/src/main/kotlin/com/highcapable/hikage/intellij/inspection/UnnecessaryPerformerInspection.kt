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

import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.dsl.detector.ViewTypeDetector
import com.highcapable.hikage.intellij.dsl.model.HikageViewAnnotation
import com.highcapable.hikage.intellij.inspection.base.BaseInspectionTool
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
 * Reports explicit performer declaration parameters that cannot affect generated code.
 */
class UnnecessaryPerformerInspection : BaseInspectionTool() {

    private companion object {
        const val UNNECESSARY_PERFORMER_MESSAGE = "The <code>performer</code> parameter is unnecessary because " +
            "the target <code>View</code> is not a <code>ViewGroup</code>"
        const val UNNECESSARY_LPARAMS_MESSAGE = "The <code>lparams</code> parameter is unnecessary because " +
            "the target <code>View</code> is not a <code>ViewGroup</code>"
    }

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR

        val viewTypeDetector = ViewTypeDetector.from(file.project)

        return object : KtVisitorVoid() {

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)

                classOrObject.annotationEntries.forEach { annotation ->
                    when {
                        DeclarationMatcher.isHikageAnnotation(annotation, HikageViewAnnotation.View.fqName) -> {
                            val isView = viewTypeDetector.isView(classOrObject)
                            val isViewGroup = isView && viewTypeDetector.isViewGroup(classOrObject)
                            holder.registerUnnecessaryArgument(
                                HikageViewAnnotation.View.performer.value(annotation),
                                isView,
                                isViewGroup,
                                HikageViewAnnotation.View.performer.parameter.argumentName,
                                UNNECESSARY_PERFORMER_MESSAGE
                            )
                            holder.registerUnnecessaryArgument(
                                HikageViewAnnotation.View.lparams.value(annotation),
                                isView,
                                isViewGroup,
                                HikageViewAnnotation.View.lparams.parameter.argumentName,
                                UNNECESSARY_LPARAMS_MESSAGE
                            )
                        }
                        DeclarationMatcher.isHikageAnnotation(annotation, HikageViewAnnotation.Declaration.fqName) -> {
                            val classLiteral = requireNotNull(HikageViewAnnotation.Declaration.view).expression(annotation)
                                ?: return@forEach
                            val isView = viewTypeDetector.isView(classLiteral)
                            val isViewGroup = isView && viewTypeDetector.isViewGroup(classLiteral)
                            holder.registerUnnecessaryArgument(
                                HikageViewAnnotation.Declaration.performer.value(annotation),
                                isView,
                                isViewGroup,
                                HikageViewAnnotation.Declaration.performer.parameter.argumentName,
                                UNNECESSARY_PERFORMER_MESSAGE
                            )
                            holder.registerUnnecessaryArgument(
                                HikageViewAnnotation.Declaration.lparams.value(annotation),
                                isView,
                                isViewGroup,
                                HikageViewAnnotation.Declaration.lparams.parameter.argumentName,
                                UNNECESSARY_LPARAMS_MESSAGE
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ProblemsHolder.registerUnnecessaryArgument(
        argument: KtValueArgument?,
        isView: Boolean,
        isViewGroup: Boolean,
        argumentName: String,
        message: String
    ) {
        if (argument == null || !isView || isViewGroup) return

        registerProblem(
            argument,
            message,
            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
            RemoveAnnotationArgumentFix(argument, argumentName)
        )
    }

    private class RemoveAnnotationArgumentFix(
        argument: KtValueArgument,
        argumentName: String
    ) : LocalQuickFixOnPsiElement(argument) {

        private val text = "Remove unnecessary '$argumentName'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val argument = startElement as? KtValueArgument ?: return
            (argument.parent as? KtValueArgumentList)?.removeArgument(argument)
        }
    }
}