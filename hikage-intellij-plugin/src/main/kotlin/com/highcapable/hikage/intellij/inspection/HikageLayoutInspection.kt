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

import com.highcapable.hikage.intellij.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.inspection.base.BaseInspectionTool
import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.utils.extension.addImport
import com.highcapable.hikage.intellij.utils.extension.findArgument
import com.highcapable.hikage.intellij.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Shared implementation for individually configurable Hikage layout inspections.
 */
abstract class HikageLayoutInspection(private val issue: Issue) : BaseInspectionTool() {

    private companion object {
        const val ID_ARGUMENT = "id"
    }

    /**
     * Selects one independently configurable Hikage layout validation rule.
     */
    enum class Issue {
        EMPTY_ID,
        DUPLICATE_ID,
        MISSING_ID,
        INCORRECT_ID_TYPE,
        INCORRECT_ROOT_TYPE,
        UNNECESSARY_NULLABLE_LOOKUP,
        SIMPLIFY_ID_CAST,
        INCORRECT_ID_CAST
    }

    /**
     * Reports empty layout IDs passed to an array or lookup accessor.
     */
    class EmptyHikageLayoutId : HikageLayoutInspection(Issue.EMPTY_ID)

    /**
     * Reports duplicate layout IDs declared in the same Hikage layout scope.
     */
    class DuplicateHikageLayoutId : HikageLayoutInspection(Issue.DUPLICATE_ID)

    /**
     * Reports statically known layout IDs that are not declared by the current layout.
     */
    class MissingHikageLayoutId : HikageLayoutInspection(Issue.MISSING_ID)

    /**
     * Reports explicit lookup generic types that differ from the declared View type.
     */
    class IncorrectHikageLayoutIdType : HikageLayoutInspection(Issue.INCORRECT_ID_TYPE)

    /**
     * Reports explicit root generic types that differ from the layout root View type.
     */
    class IncorrectHikageLayoutRootType : HikageLayoutInspection(Issue.INCORRECT_ROOT_TYPE)

    /**
     * Replaces nullable lookups for IDs that are statically guaranteed to exist.
     */
    class UnnecessaryHikageLayoutNullableLookup : HikageLayoutInspection(Issue.UNNECESSARY_NULLABLE_LOOKUP)

    /**
     * Replaces array-access casts with Hikage's typed lookup functions.
     */
    class SimplifyHikageLayoutIdCast : HikageLayoutInspection(Issue.SIMPLIFY_ID_CAST)

    /**
     * Reports array-access casts that cannot match a known layout ID's View type.
     */
    class IncorrectHikageLayoutIdCast : HikageLayoutInspection(Issue.INCORRECT_ID_CAST)

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file !is KtFile) return PsiElementVisitor.EMPTY_VISITOR

        val resolver = HikageLayoutResolver.from(holder.project)
        val declaredIds = hashMapOf<PsiElement, MutableMap<String, MutableList<KtExpression>>>()
        val reportedDuplicateIds = hashSetOf<PsiElement>()

        return object : KtVisitorVoid() {

            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                super.visitArrayAccessExpression(expression)

                val access = expression.layoutIdAccess(resolver) ?: return
                if (access.id.isBlank()) {
                    holder.registerEmptyId(access.idExpression)
                    return
                }

                holder.inspectRequiredId(access.receiver, access.idExpression, access.id, resolver)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                holder.reportDuplicateId(expression, resolver, declaredIds, reportedDuplicateIds)
                expression.layoutRootAccess()?.let { access ->
                    val viewClass = resolver.resolve(access.receiver)?.root?.viewClass ?: return
                    holder.registerWrongRootType(access, viewClass, resolver)
                    return
                }

                val access = expression.layoutIdAccess(resolver) ?: return
                if (access.id.isBlank()) {
                    holder.registerEmptyId(access.idExpression)
                    return
                }

                val model = resolver.resolve(access.receiver) ?: return
                val id = model.ids.firstOrNull { candidate -> candidate.name == access.id }
                if (access.kind == LookupKind.GET_OR_NULL) {
                    val viewClass = id?.takeIf { candidate -> candidate.isAlwaysPresent }?.viewClass ?: return
                    holder.registerAlwaysPresent(access, viewClass)
                    return
                }
                if (id == null) {
                    holder.registerMissingId(access.idExpression, access.id)
                    return
                }

                val typeReference = access.typeReference ?: return
                val viewClass = id.viewClass ?: return

                holder.registerWrongType(access, typeReference, viewClass, resolver)
            }

            override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
                super.visitBinaryWithTypeRHSExpression(expression)

                when (val result = expression.safeTypeCastResult(resolver)) {
                    is SafeTypeCastResult.Replacement -> {
                        if (issue != Issue.SIMPLIFY_ID_CAST) return
                        val target = expression.parent as? KtParenthesizedExpression ?: expression

                        holder.registerProblem(
                            target,
                            "Can be replaced with safe type cast <code>${result.suggestion}</code>",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            ReplaceSafeTypeCastFix(target, result.expression)
                        )
                    }
                    is SafeTypeCastResult.WrongType -> {
                        if (issue != Issue.INCORRECT_ID_CAST) return
                        val target = expression.parent as? KtParenthesizedExpression ?: expression
                        val expectedName = result.viewClass.name ?: return

                        holder.registerProblem(
                            target,
                            "Incorrect type of ID <code>${StringUtil.escapeXmlEntities(result.id)}</code>. " +
                                "Expected <code>$expectedName</code>",
                            ProblemHighlightType.GENERIC_ERROR,
                            ReplaceWrongCastTypeFix(
                                target,
                                result.receiver,
                                result.idArgument,
                                result.lookupFunctionName,
                                result.viewClass
                            )
                        )
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun ProblemsHolder.inspectRequiredId(
        receiver: KtExpression,
        idExpression: KtExpression,
        id: String,
        resolver: HikageLayoutResolver
    ) {
        if (issue != Issue.MISSING_ID) return
        val model = resolver.resolve(receiver) ?: return
        if (model.ids.none { candidate -> candidate.name == id }) registerMissingId(idExpression, id)
    }

    private fun ProblemsHolder.reportDuplicateId(
        call: KtCallExpression,
        resolver: HikageLayoutResolver,
        declaredIds: MutableMap<PsiElement, MutableMap<String, MutableList<KtExpression>>>,
        reportedIds: MutableSet<PsiElement>
    ) {
        if (issue != Issue.DUPLICATE_ID) return
        val method = call.resolveMethod() ?: return
        if (!DeclarationMatcher.isHikagableFunction(method)) return

        val expression = call.findArgument(method, ID_ARGUMENT)?.getArgumentExpression() ?: return
        val id = resolver.resolveIdValue(expression)?.takeIf(String::isNotBlank) ?: return
        val scope = resolver.findDeclarationScope(call) ?: return
        val usages = declaredIds.getOrPut(scope) { hashMapOf() }.getOrPut(id) { mutableListOf() }
        usages += expression
        usages.sortedBy { usage -> usage.textOffset }.forEachIndexed { index, usage ->
            if (index == 0 || !reportedIds.add(usage)) return@forEachIndexed
            registerProblem(
                usage,
                "ID <code>${StringUtil.escapeXmlEntities(id)}</code> is duplicated in the same Hikage layout",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun ProblemsHolder.registerMissingId(expression: KtExpression, id: String) {
        if (issue != Issue.MISSING_ID) return

        // ProblemsHolder cannot override Kotlin string syntax highlighting; enforce the native unresolved-reference style.
        val descriptor = ProblemDescriptorBase(
            expression,
            expression,
            "Cannot resolve ID <code>${StringUtil.escapeXmlEntities(id)}</code>",
            emptyArray(),
            ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
            false,
            null,
            true,
            isOnTheFly
        )
        descriptor.setTextAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES)
        registerProblem(descriptor)
    }

    private fun ProblemsHolder.registerEmptyId(expression: KtExpression) {
        if (issue != Issue.EMPTY_ID) return
        registerProblem(expression, "ID must not be empty", ProblemHighlightType.GENERIC_ERROR)
    }

    private fun ProblemsHolder.registerWrongType(
        access: LayoutIdCallAccess,
        typeReference: KtTypeReference,
        viewClass: PsiClass,
        resolver: HikageLayoutResolver
    ) {
        if (issue != Issue.INCORRECT_ID_TYPE) return
        val expectedName = viewClass.name ?: return

        registerWrongLookupType(
            access.call,
            typeReference,
            viewClass,
            "Incorrect type of ID <code>${StringUtil.escapeXmlEntities(access.id)}</code>. Expected <code>$expectedName</code>",
            resolver
        )
    }

    private fun ProblemsHolder.registerWrongRootType(
        access: LayoutRootCallAccess,
        viewClass: PsiClass,
        resolver: HikageLayoutResolver
    ) {
        if (issue != Issue.INCORRECT_ROOT_TYPE) return
        val expectedName = viewClass.name ?: return

        registerWrongLookupType(
            access.call,
            access.typeReference,
            viewClass,
            "Incorrect type of root. Expected <code>$expectedName</code>",
            resolver
        )
    }

    private fun ProblemsHolder.registerWrongLookupType(
        call: KtCallExpression,
        typeReference: KtTypeReference,
        viewClass: PsiClass,
        message: String,
        resolver: HikageLayoutResolver
    ) {
        val currentClass = resolver.resolveTypeClass(typeReference) ?: return
        if (currentClass.isBaseView()) return
        if (viewClass.canCastTo(currentClass)) return

        registerProblem(typeReference, message, ProblemHighlightType.GENERIC_ERROR, ReplaceLookupTypeFix(call, viewClass))
    }

    private fun ProblemsHolder.registerAlwaysPresent(
        access: LayoutIdCallAccess,
        viewClass: PsiClass
    ) {
        if (issue != Issue.UNNECESSARY_NULLABLE_LOOKUP) return

        registerProblem(
            access.call.calleeExpression ?: access.call,
            "Unnecessary use of <code>${HikageSymbols.HIKAGE_GET_OR_NULL_FUNCTION_NAME}</code> for ID <code>${StringUtil.escapeXmlEntities(access.id)}</code>",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            ReplaceGetOrNullFix(access.call, viewClass)
        )
    }

    private fun KtArrayAccessExpression.layoutIdAccess(resolver: HikageLayoutResolver): LayoutIdArrayAccess? {
        val receiver = arrayExpression ?: return null
        val idExpression = indexExpressions.singleOrNull() ?: return null
        val id = resolver.resolveIdValue(idExpression) ?: return null

        return LayoutIdArrayAccess(receiver, idExpression, id)
    }

    private fun KtCallExpression.layoutIdAccess(resolver: HikageLayoutResolver): LayoutIdCallAccess? {
        val method = resolveMethod() ?: return null
        if (method.containingClass?.qualifiedName != HikageSymbols.HIKAGE) return null

        val kind = when (calleeExpression?.text) {
            HikageSymbols.HIKAGE_GET_FUNCTION_NAME -> LookupKind.GET
            HikageSymbols.HIKAGE_GET_OR_NULL_FUNCTION_NAME -> LookupKind.GET_OR_NULL
            else -> return null
        }
        val argument = findArgument(method, ID_ARGUMENT) ?: return null
        val idExpression = argument.getArgumentExpression() ?: return null
        val id = resolver.resolveIdValue(idExpression) ?: return null
        val qualified = parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression !== this) return null

        return LayoutIdCallAccess(
            receiver = qualified.receiverExpression,
            call = this,
            idExpression = idExpression,
            id = id,
            typeReference = typeArgumentList?.arguments?.singleOrNull()?.typeReference,
            kind = kind
        )
    }

    private fun KtCallExpression.layoutRootAccess(): LayoutRootCallAccess? {
        val method = resolveMethod() ?: return null
        if (method.containingClass?.qualifiedName != HikageSymbols.HIKAGE ||
            calleeExpression?.text != HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME
        ) return null

        val typeReference = typeArgumentList?.arguments?.singleOrNull()?.typeReference ?: return null
        val qualified = parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression !== this) return null

        return LayoutRootCallAccess(qualified.receiverExpression, this, typeReference)
    }

    private fun KtBinaryExpressionWithTypeRHS.safeTypeCastResult(
        resolver: HikageLayoutResolver
    ): SafeTypeCastResult? {
        val operation = operationReference.getReferencedNameElementType()
        if (operation != KtTokens.AS_KEYWORD && operation != KtTokens.AS_SAFE) return null

        val arrayAccess = left as? KtArrayAccessExpression ?: return null
        val receiver = arrayAccess.arrayExpression ?: return null
        if (!resolver.isHikage(receiver)) return null

        val receiverContent = arrayAccess.indexExpressions.singleOrNull()?.text ?: return null
        val id = arrayAccess.indexExpressions.singleOrNull()?.let(resolver::resolveIdValue)
        if (id?.isBlank() == true) return null

        val model = resolver.resolve(receiver)
        val layoutId = id?.let { value -> model?.ids?.firstOrNull { candidate -> candidate.name == value } }
        if (id != null && model != null && layoutId == null) return null

        val viewClass = layoutId?.viewClass
        val typeReference = right ?: return null
        val castType = typeReference.text.removeSuffix("?")
        val castClass = resolver.resolveTypeClass(typeReference)
        if (castClass.isBaseView()) return null

        val functionName = if (operation == KtTokens.AS_SAFE || text.endsWith("?"))
            HikageSymbols.HIKAGE_GET_OR_NULL_FUNCTION_NAME
        else HikageSymbols.HIKAGE_GET_FUNCTION_NAME
        if (viewClass != null && castClass != null && !viewClass.canCastTo(castClass))
            return SafeTypeCastResult.WrongType(receiver.text, receiverContent, id, functionName, viewClass)

        return SafeTypeCastResult.Replacement(
            expression = "${receiver.text}.$functionName<$castType>($receiverContent)",
            suggestion = "Hikage.$functionName&lt;$castType&gt;"
        )
    }

    private fun PsiClass.canCastTo(target: PsiClass) = this == target ||
        qualifiedName == target.qualifiedName ||
        isInheritor(target, true)

    private fun PsiClass?.isBaseView() = this?.qualifiedName == AndroidSymbols.VIEW_CLASS

    private sealed class SafeTypeCastResult {

        data class Replacement(
            val expression: String,
            val suggestion: String
        ) : SafeTypeCastResult()

        data class WrongType(
            val receiver: String,
            val idArgument: String,
            val id: String,
            val lookupFunctionName: String,
            val viewClass: PsiClass
        ) : SafeTypeCastResult()
    }

    private data class LayoutIdArrayAccess(
        val receiver: KtExpression,
        val idExpression: KtExpression,
        val id: String
    )

    private data class LayoutIdCallAccess(
        val receiver: KtExpression,
        val call: KtCallExpression,
        val idExpression: KtExpression,
        val id: String,
        val typeReference: KtTypeReference?,
        val kind: LookupKind
    )

    private data class LayoutRootCallAccess(
        val receiver: KtExpression,
        val call: KtCallExpression,
        val typeReference: KtTypeReference
    )

    private enum class LookupKind {
        GET,
        GET_OR_NULL
    }

    private class ReplaceLookupTypeFix(call: KtCallExpression, viewClass: PsiClass) : LocalQuickFixOnPsiElement(call) {

        private val viewClassPointer = SmartPointerManager.getInstance(viewClass.project).createSmartPsiElementPointer(viewClass)
        private val text = "Change to '${viewClass.name}'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val targetFile = file as? KtFile ?: return
            val viewClass = viewClassPointer.element ?: return
            val type = HikageLayoutResolver.from(project).createTypeReference(targetFile, viewClass) ?: return
            val typeArgumentList = call.typeArgumentList ?: return

            val psiFactory = KtPsiFactory(project)
            val replacement = (psiFactory.createExpression("get<${type.reference}>()") as? KtCallExpression)
                ?.typeArgumentList
                ?: return

            typeArgumentList.replace(replacement)
            type.importFqName?.let { fqName -> targetFile.addImport(psiFactory, fqName) }
        }
    }

    private class ReplaceGetOrNullFix(call: KtCallExpression, viewClass: PsiClass) : LocalQuickFixOnPsiElement(call) {

        private val viewClassPointer = SmartPointerManager.getInstance(viewClass.project).createSmartPsiElementPointer(viewClass)
        private val text = "Replace with '${HikageSymbols.HIKAGE_GET_FUNCTION_NAME}<${viewClass.name}>'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val targetFile = file as? KtFile ?: return
            val viewClass = viewClassPointer.element ?: return
            val type = HikageLayoutResolver.from(project).createTypeReference(targetFile, viewClass) ?: return

            val arguments = call.valueArgumentList?.text ?: return
            val psiFactory = KtPsiFactory(project)
            call.replace(psiFactory.createExpression(
                "${HikageSymbols.HIKAGE_GET_FUNCTION_NAME}<${type.reference}>$arguments"
            ))
            type.importFqName?.let { fqName -> targetFile.addImport(psiFactory, fqName) }
        }
    }

    private class ReplaceSafeTypeCastFix(
        target: PsiElement,
        private val replacement: String
    ) : LocalQuickFixOnPsiElement(target) {

        private val text = "Replace with '$replacement'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            (startElement as? KtExpression)?.replace(KtPsiFactory(project).createExpression(replacement))
        }
    }

    private class ReplaceWrongCastTypeFix(
        target: PsiElement,
        private val receiver: String,
        private val idArgument: String,
        private val lookupFunctionName: String,
        viewClass: PsiClass
    ) : LocalQuickFixOnPsiElement(target) {

        private val viewClassPointer = SmartPointerManager.getInstance(viewClass.project).createSmartPsiElementPointer(viewClass)
        private val previewReplacement = "$receiver.$lookupFunctionName<${viewClass.name}>($idArgument)"
        private val text = "Replace with '$previewReplacement'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val target = startElement as? KtExpression ?: return
            val targetFile = file as? KtFile ?: return
            val viewClass = viewClassPointer.element ?: return

            val type = HikageLayoutResolver.from(project).createTypeReference(targetFile, viewClass) ?: return
            val replacement = "$receiver.$lookupFunctionName<${type.reference}>($idArgument)"
            target.replace(KtPsiFactory(project).createExpression(replacement))
            type.importFqName?.let { fqName -> targetFile.addImport(KtPsiFactory(project), fqName) }
        }
    }
}