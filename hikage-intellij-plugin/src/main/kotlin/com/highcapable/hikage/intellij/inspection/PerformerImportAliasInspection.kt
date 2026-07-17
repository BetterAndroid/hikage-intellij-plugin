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

import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.ProjectService
import com.highcapable.hikage.intellij.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Reports unnecessary aliases for active, generated Hikage performer imports.
 */
class PerformerImportAliasInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!ProjectService.getInstance(file.project).isHikageProject()) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            override fun visitKtFile(file: KtFile) {
                holder.inspectPerformerAliases(file)
            }
        }
    }

    private fun ProblemsHolder.inspectPerformerAliases(file: KtFile) {
        val aliasImports = file.importDirectives.mapNotNull { it.toPerformerAliasImport() }
            .associateBy(PerformerAliasImport::aliasName)
        if (aliasImports.isEmpty()) return

        val functionNames = aliasImports.values.mapTo(hashSetOf(), PerformerAliasImport::functionName)
        val conflictingFunctionNames = buildSet {
            file.importDirectives.mapNotNullTo(this) { it.toConflictingFunctionName() }
            file.collectDescendantsOfType<KtNamedFunction>()
                .mapNotNullTo(this) { it.name?.takeIf(functionNames::contains) }
        }
        val usages = file.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
            val aliasImport = aliasImports[call.calleeExpression?.text] ?: return@mapNotNull null
            if (aliasImport.functionName in conflictingFunctionNames) return@mapNotNull null
            if (call.resolveMethod()?.let(DeclarationMatcher::isHikagableFunction) != true) return@mapNotNull null
            PerformerAliasUsage(aliasImport, call.calleeExpression ?: return@mapNotNull null)
        }.groupBy { it.aliasImport.aliasName }

        usages.forEach { (aliasName, aliasUsages) ->
            val aliasImport = aliasImports[aliasName] ?: return@forEach
            val message = "Do not alias imports from performers. Use <code>${aliasImport.functionName}</code> directly"
            val fix = RemovePerformerAliasFix(
                aliasImport,
                aliasUsages.mapTo(hashSetOf()) { it.calleeExpression.textRange.startOffset }
            )
            registerProblem(aliasImport.importDirective, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fix)
            aliasUsages.forEach { usage ->
                registerProblem(usage.calleeExpression, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fix)
            }
        }
    }

    private fun KtImportDirective.toPerformerAliasImport(): PerformerAliasImport? {
        val aliasName = aliasName ?: return null
        val functionImportName = importedFqName?.asString() ?: return null
        if (!functionImportName.startsWith(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX)) return null
        val functionName = functionImportName.substringAfterLast('.')
        if (aliasName == functionName) return null

        return PerformerAliasImport(this, aliasName, functionName, functionImportName)
    }

    private fun KtImportDirective.toConflictingFunctionName(): String? {
        if (aliasName != null || isAllUnder) return null
        val importedFqName = importedFqName?.asString() ?: return null
        if (importedFqName.startsWith(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX)) return null
        if (!importedReference?.mainReference?.resolve().isFunction()) return null

        return importedFqName.substringAfterLast('.').takeIf { it.isNotEmpty() }
    }

    private fun PsiElement?.isFunction() = this is PsiMethod || this is KtNamedFunction

    private data class PerformerAliasImport(
        val importDirective: KtImportDirective,
        val aliasName: String,
        val functionName: String,
        val functionImportName: String
    )

    private data class PerformerAliasUsage(
        val aliasImport: PerformerAliasImport,
        val calleeExpression: KtExpression
    )

    private class RemovePerformerAliasFix(
        private val aliasImport: PerformerAliasImport,
        private val usageOffsets: Set<Int>
    ) : LocalQuickFix {

        private val text = "Remove alias '${aliasImport.aliasName}'"

        override fun getFamilyName() = text
        override fun getName() = text

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement.containingFile as? KtFile ?: return
            val importDirective = file.importDirectives.firstOrNull { directive ->
                directive.aliasName == aliasImport.aliasName &&
                    directive.importedFqName?.asString() == aliasImport.functionImportName
            } ?: return
            val psiFactory = KtPsiFactory(project)
            val callees = file.collectDescendantsOfType<KtCallExpression>().mapNotNull { call ->
                call.calleeExpression?.takeIf {
                    it.text == aliasImport.aliasName && it.textRange.startOffset in usageOffsets
                }
            }
            importDirective.replace(
                psiFactory.createImportDirective(ImportPath(FqName(aliasImport.functionImportName), false))
            )
            callees.filter(PsiElement::isValid).forEach { callee ->
                callee.replace(psiFactory.createExpression(aliasImport.functionName))
            }
        }

        override fun getFileModifierForPreview(target: PsiFile) = RemovePerformerAliasFix(aliasImport, usageOffsets)
    }
}