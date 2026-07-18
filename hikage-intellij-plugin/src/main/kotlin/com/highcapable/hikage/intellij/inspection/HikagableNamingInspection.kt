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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.inspection.base.BaseInspectionTool
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandlerRegistry
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports `Hikagable` declarations that should use PascalCase names.
 */
class HikagableNamingInspection : BaseInspectionTool() {

    private companion object {

        val PASCAL_CASE_PATTERN = "[A-Z][A-Za-z0-9]*".toRegex()

        const val FUNCTION_DESCRIPTION = "Hikagable functions should start with an uppercase letter"
        const val PROPERTY_DESCRIPTION = "Hikagable properties should start with an uppercase letter"
        const val FUNCTION_QUICK_FIX_TEXT = "Rename Hikagable function"
        const val PROPERTY_QUICK_FIX_TEXT = "Rename Hikagable property"
    }

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (file.language != KotlinLanguage.INSTANCE) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                if (!DeclarationMatcher.isHikagableFunction(function)) return
                holder.registerIfNotPascalCase(function, FUNCTION_DESCRIPTION, FUNCTION_QUICK_FIX_TEXT)
            }

            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)
                if (!DeclarationMatcher.isDirectHikageFactoryProperty(property)) return
                holder.registerIfNotPascalCase(property, PROPERTY_DESCRIPTION, PROPERTY_QUICK_FIX_TEXT)
            }
        }
    }

    private fun ProblemsHolder.registerIfNotPascalCase(element: KtNamedDeclaration, description: String, quickFixText: String) {
        val nameIdentifier = element.nameIdentifier ?: return
        val name = element.name ?: return
        if (name.isPascalCaseIdentifier()) return

        registerProblem(nameIdentifier, description, RenameHikageDeclarationFix(element, quickFixText))
    }

    private fun String.isPascalCaseIdentifier() = matches(PASCAL_CASE_PATTERN)

    private class RenameHikageDeclarationFix(element: PsiElement, private val quickFixText: String) : LocalQuickFixOnPsiElement(element) {

        private companion object {

            const val PROHIBITED_ANALYSIS_EXCEPTION = "org.jetbrains.kotlin.analysis.api.impl.base.sessions.ProhibitedAnalysisException"

            var isWriteActionFallbackEnabled = false
        }

        override fun getFamilyName() = quickFixText
        override fun getText() = quickFixText
        override fun availableInBatchMode() = false
        override fun startInWriteAction() = !isWriteActionFallbackEnabled
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
        override fun getFileModifierForPreview(target: PsiFile) = null

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            runCatching {
                invokeRename(project, file, startElement)
            }.onFailure { throwable ->
                // Fallback if the rename action is prohibited due to analysis API restrictions.
                if (!throwable.isProhibitedAnalysisException()) throw throwable
                isWriteActionFallbackEnabled = true
                ApplicationManager.getApplication().invokeLater(
                    { invokeRename(project, file, startElement) },
                    ModalityState.current()
                )
            }
        }

        private fun invokeRename(project: Project, file: PsiFile, startElement: PsiElement) {
            val dataContext = startElement.renameDataContext(project, file)
            val renameHandler = RenameHandlerRegistry.getInstance().getRenameHandler(dataContext) ?: return
            renameHandler.invoke(project, arrayOf(startElement), dataContext)
        }

        private fun Throwable.isProhibitedAnalysisException(): Boolean {
            var current: Throwable? = this
            while (current != null) {
                if (current.javaClass.name == PROHIBITED_ANALYSIS_EXCEPTION) return true
                current = current.cause
            }
            return false
        }

        private fun PsiElement.renameDataContext(project: Project, file: PsiFile): DataContext {
            val baseContext = SimpleDataContext.getProjectContext(project)
            return SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.PSI_ELEMENT, this)
                .setParent(baseContext)
                .build()
        }
    }
}