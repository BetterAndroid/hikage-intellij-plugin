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
import com.highcapable.hikage.dsl.model.HikageViewAnnotation
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.dsl.validation.PerformerValidator
import com.highcapable.hikage.inspection.base.BaseInspectionTool
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.addImport
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Suggests active generated performers or creates missing declarations for generic Hikage `View`
 * and `ViewGroup` calls.
 */
class GeneratedPerformerInspection : BaseInspectionTool() {

    private companion object {
        val GENERIC_VIEW_FUNCTION_NAMES = setOf("View", "ViewGroup")
    }

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val declarations = PerformerDeclarations.resolve(file.project).associateBy(PerformerDeclaration::viewClass)
        val duplicateViewClasses = PerformerDeclarations.duplicateViewClasses(file.project)
        val validator = PerformerValidator.from(file.project)

        return object : KtVisitorVoid() {

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                val callee = expression.calleeExpression ?: return
                if (callee.text !in GENERIC_VIEW_FUNCTION_NAMES) return

                val typeArgumentList = expression.typeArgumentList ?: return
                val method = expression.resolveMethod() ?: return
                if (!DeclarationMatcher.isHikagableFunction(method)) return

                val genericView = expression.resolveGenericView(
                    method,
                    hasExplicitLparams = callee.text == "ViewGroup" && typeArgumentList.arguments.size > 1
                ) ?: return
                if (validator.validate(PerformerValidator.Type.VIEW, genericView.viewClass) != PerformerValidator.Result.VALID) return
                if (genericView.lparamsClass != null &&
                    validator.validate(PerformerValidator.Type.LPARAMS, genericView.lparamsClass) != PerformerValidator.Result.VALID
                ) return

                val viewClassName = genericView.viewClass.qualifiedName ?: return
                val expressionStartOffset = expression.textRange.startOffset
                val problemRange = TextRange(
                    callee.textRange.startOffset - expressionStartOffset,
                    typeArgumentList.textRange.endOffset - expressionStartOffset
                )
                declarations[viewClassName]?.let { declaration ->
                    holder.registerProblem(
                        expression,
                        descriptionTemplate(declaration.functionName),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        problemRange,
                        ReplaceWithGeneratedPerformerFix(expression, declaration)
                    )
                    return
                }
                if (viewClassName in duplicateViewClasses) return
                val missingDeclaration = ViewDeclaration.from(
                    viewClassName,
                    alias = null,
                    isViewGroup = callee.text == "ViewGroup"
                ) ?: return

                val lparamsReference = genericView.lparamsClass?.let { lparamsClass ->
                    lparamsClass.toKotlinClassReference() ?: return
                }
                val projectView = genericView.viewClass.projectDeclaration(file.project)
                if (projectView != null) {
                    if (projectView.hasHikageViewAnnotation()) return
                    val viewName = projectView.name ?: return
                    holder.registerProblem(
                        expression,
                        descriptionTemplate(missingDeclaration.functionName),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        problemRange,
                        MakeHikagableFix(
                            expression,
                            projectView,
                            viewName,
                            lparamsReference,
                            missingDeclaration
                        )
                    )
                    return
                }

                val viewReference = genericView.viewClass.toKotlinClassReference() ?: return
                val viewName = genericView.viewClass.name ?: return
                val declarationName = "${viewName}Declaration"
                holder.registerProblem(
                    expression,
                    descriptionTemplate(missingDeclaration.functionName),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    problemRange,
                    CreateHikageViewDeclarationFix(
                        expression,
                        viewReference,
                        lparamsReference,
                        declarationName,
                        missingDeclaration
                    )
                )
            }
        }
    }

    private fun KtCallExpression.resolveGenericView(method: PsiMethod, hasExplicitLparams: Boolean): GenericView? {
        val typeParameters = method.typeParameterList?.typeParameters ?: return null
        val viewTypeParameterIndex = typeParameters.indexOfFirst { parameter ->
            parameter.extendsListTypes.any { type ->
                when (type.canonicalClassName()) {
                    AndroidSymbols.VIEW_CLASS,
                    AndroidSymbols.VIEW_GROUP_CLASS -> true
                    else -> false
                }
            }
        }
        if (viewTypeParameterIndex < 0) return null
        val typeArguments = typeArgumentList?.arguments ?: return null
        val viewClass = typeArguments.getOrNull(viewTypeParameterIndex)?.typeReference?.resolvePsiClass() ?: return null
        if (!hasExplicitLparams) return GenericView(viewClass, null)

        val lparamsTypeParameterIndex = viewTypeParameterIndex + 1
        val isLayoutParams = typeParameters.getOrNull(lparamsTypeParameterIndex)?.extendsListTypes?.any { type ->
            type.canonicalClassName() == AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        } == true
        if (!isLayoutParams) return null
        val lparamsClass = typeArguments.getOrNull(lparamsTypeParameterIndex)?.typeReference?.resolvePsiClass() ?: return null

        return GenericView(viewClass, lparamsClass)
    }

    private fun KtTypeReference.resolvePsiClass(): PsiClass? {
        // Resolving UAST's PsiClassType routes Kotlin source classes through KotlinFullClassNameIndex.
        // Use the inspection-safe Analysis API path so a stale stub index cannot be repeatedly amplified here.
        val declaration = runCatching {
            analyze(this) {
                (type as? KaClassType)?.symbol?.psi
            }
        }.getOrNull() ?: return null

        return when (declaration) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass()
            else -> null
        }
    }

    private fun descriptionTemplate(functionName: String) = "Can be simplified to <code>$functionName</code>"

    private fun PsiClass.projectDeclaration(project: Project): KtClassOrObject? {
        val declaration = navigationElement as? KtClassOrObject ?: return null
        val virtualFile = declaration.containingFile.virtualFile ?: return null
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return null

        return declaration
    }

    private fun KtClassOrObject.hasHikageViewAnnotation() = annotationEntries.any { annotation ->
        DeclarationMatcher.isHikageAnnotation(annotation, HikageSymbols.HIKAGE_VIEW_ANNOTATION)
    }

    private fun PsiClass.toKotlinClassReference(): KotlinClassReference? {
        val classes = generateSequence(this) { declaration -> declaration.containingClass }.toList().asReversed()
        val importName = classes.firstOrNull()?.qualifiedName ?: return null
        val name = classes.mapNotNull(PsiClass::getName).joinToString(".").takeIf(String::isNotBlank) ?: return null

        return KotlinClassReference(name, importName)
    }

    private data class KotlinClassReference(
        val name: String,
        val importName: String
    )

    private data class GenericView(
        val viewClass: PsiClass,
        val lparamsClass: PsiClass?
    )

    private fun KtCallExpression.replaceWithPerformer(
        psiFactory: KtPsiFactory,
        declaration: ViewDeclaration,
        file: KtFile
    ) {
        val callee = calleeExpression ?: return
        val typeArgumentList = typeArgumentList ?: return
        typeArgumentList.delete()
        callee.replace(psiFactory.createExpression(declaration.functionName))
        file.addImport(psiFactory, declaration.generatedKey)
    }

    private inner class MakeHikagableFix(
        call: KtCallExpression,
        declaration: KtClassOrObject,
        viewName: String,
        private val lparamsReference: KotlinClassReference?,
        private val performerDeclaration: ViewDeclaration
    ) : LocalQuickFixOnPsiElement(call) {

        private val declarationPointer = SmartPointerManager.getInstance(declaration.project).createSmartPsiElementPointer(declaration)
        private val text = "Add '@${HikageSymbols.HIKAGE_VIEW_ANNOTATION_NAME}' to '$viewName'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val callFile = file as? KtFile ?: return
            val declaration = declarationPointer.element ?: return
            if (declaration.hasHikageViewAnnotation()) return
            val targetFile = declaration.containingKtFile
            val psiFactory = KtPsiFactory(project)
            val lparamsArgument = lparamsReference?.let { reference ->
                "(${HikageViewAnnotation.Parameter.LPARAMS.argumentName} = ${reference.name}::class)"
            }.orEmpty()
            declaration.addAnnotationEntry(
                psiFactory.createAnnotationEntry("@${HikageSymbols.HIKAGE_VIEW_ANNOTATION_NAME}$lparamsArgument")
            )
            targetFile.addImport(psiFactory, HikageSymbols.HIKAGE_VIEW_ANNOTATION)
            lparamsReference?.let { reference -> targetFile.addImport(psiFactory, reference.importName) }
            call.replaceWithPerformer(psiFactory, performerDeclaration, callFile)
        }
    }

    private inner class CreateHikageViewDeclarationFix(
        call: KtCallExpression,
        private val viewReference: KotlinClassReference,
        private val lparamsReference: KotlinClassReference?,
        private val declarationName: String,
        private val performerDeclaration: ViewDeclaration
    ) : LocalQuickFixOnPsiElement(call) {

        private val text = "Create '$declarationName'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val targetFile = file as? KtFile ?: return
            if (targetFile.declarations.any { declaration -> declaration.name == declarationName }) return
            val psiFactory = KtPsiFactory(project)
            val lparamsArgument = lparamsReference?.let { reference ->
                ", ${HikageViewAnnotation.Parameter.LPARAMS.argumentName} = ${reference.name}::class"
            }.orEmpty()
            val declaration = psiFactory.createDeclaration<KtObjectDeclaration>(
                "@${HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION_NAME}(${viewReference.name}::class$lparamsArgument)\n" +
                    "private object $declarationName"
            )
            targetFile.addImport(psiFactory, HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION)
            targetFile.addImport(psiFactory, viewReference.importName)
            lparamsReference?.let { reference -> targetFile.addImport(psiFactory, reference.importName) }
            targetFile.add(psiFactory.createNewLine())
            targetFile.add(declaration)
            call.replaceWithPerformer(psiFactory, performerDeclaration, targetFile)
        }
    }

    private inner class ReplaceWithGeneratedPerformerFix(
        call: KtCallExpression,
        private val declaration: PerformerDeclaration
    ) : LocalQuickFixOnPsiElement(call) {

        private val text = "Replace with '${declaration.functionName}'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val targetFile = file as? KtFile ?: return
            val psiFactory = KtPsiFactory(project)

            call.replaceWithPerformer(psiFactory, declaration.declaration, targetFile)
        }
    }
}