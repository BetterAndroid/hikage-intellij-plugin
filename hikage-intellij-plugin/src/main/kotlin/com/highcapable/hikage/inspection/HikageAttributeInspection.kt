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

import com.android.resources.ResourceType
import com.android.tools.idea.projectsystem.SourceProviderManager
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.inspection.base.BaseInspectionTool
import com.highcapable.hikage.project.Coordinates
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.addImport
import com.highcapable.hikage.utils.extension.failOpen
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.io.File
import javax.lang.model.SourceVersion

/**
 * Shared implementation for individually configurable Hikage attribute inspections.
 */
abstract class HikageAttributeInspection(private val issue: Issue) : BaseInspectionTool() {

    private companion object {

        const val NAMESPACE_FUNCTION = "namespace"
        const val NAME_ARGUMENT = "name"
        const val ATTRS_ARGUMENT = "attrs"
        const val LPARAMS_ARGUMENT = "lparams"
        const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
        const val ATTRIBUTE_STRING_MAX_LENGTH = 0x7FFF
        const val ANDROID_NAMESPACE = "android"
        const val APP_NAMESPACE = "app"
        const val ID_RESOURCE_TYPE = "id"
        const val ATTR_RESOURCE_TYPE = "attr"
        const val IDS_XML_FILE = "ids.xml"

        val COLOR_VALUE_REGEX = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$".toRegex()
    }

    enum class Issue {
        MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY,
        MISSING_NAMESPACE,
        DUPLICATE,
        NAMESPACE,
        INEFFECTIVE_LAYOUT_ATTRIBUTE,
        CREATE_ID,
        MISSING_ID,
        INVALID_NAME,
        UNKNOWN_ATTRIBUTE,
        INVALID_RESOURCE_REFERENCE,
        INVALID_COLOR_VALUE,
        TOO_LONG_STRING
    }

    /**
     * Reports non-empty performer attributes when the current module lacks the runtime attribute dependency.
     */
    class MissingHikageRuntimeAttributeDependency : HikageAttributeInspection(Issue.MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY)

    /**
     * Reports root-scope Hikage attributes that do not declare a namespace.
     */
    class MissingHikageAttributeNamespace : HikageAttributeInspection(Issue.MISSING_NAMESPACE)

    /**
     * Reports duplicate Hikage attributes that can execute in the same attribute scope.
     */
    class DuplicateHikageAttribute : HikageAttributeInspection(Issue.DUPLICATE)

    /**
     * Suggests namespace shortcuts and reports inconsistent scoped attribute prefixes.
     */
    class ReplaceWithHikageAttributeNamespaceShortcuts : HikageAttributeInspection(Issue.NAMESPACE)

    /**
     * Reports `layout_` attributes made ineffective by an explicit `lparams`.
     */
    class IneffectiveHikageLayoutAttribute : HikageAttributeInspection(Issue.INEFFECTIVE_LAYOUT_ATTRIBUTE)

    /**
     * Reports attempts to create ID resources from runtime Hikage attributes.
     */
    class CreateIdInHikageAttribute : HikageAttributeInspection(Issue.CREATE_ID)

    /**
     * Reports Hikage attribute ID references that are absent from the application resources.
     */
    class MissingIdInHikageAttribute : HikageAttributeInspection(Issue.MISSING_ID)

    /**
     * Reports malformed Hikage attribute names and namespace prefixes.
     */
    class InvalidHikageAttributeName : HikageAttributeInspection(Issue.INVALID_NAME)

    /**
     * Reports attributes missing from Android definitions or unavailable to the target View scope.
     */
    class UnknownHikageAttribute : HikageAttributeInspection(Issue.UNKNOWN_ATTRIBUTE)

    /**
     * Reports malformed resource and theme-attribute references in Hikage attribute values.
     */
    class InvalidHikageAttributeResourceReference : HikageAttributeInspection(Issue.INVALID_RESOURCE_REFERENCE)

    /**
     * Reports color values that do not use a supported Android hexadecimal format.
     */
    class InvalidHikageAttributeColorValue : HikageAttributeInspection(Issue.INVALID_COLOR_VALUE)

    /**
     * Reports Hikage attribute strings that exceed the binary XML string-pool limit.
     */
    class TooLongHikageAttributeString : HikageAttributeInspection(Issue.TOO_LONG_STRING)

    override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val isAttributeEnabled = HikageRuntimeAttributeGate.isEnabled(file)

        if (issue == Issue.MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY && isAttributeEnabled ||
            issue != Issue.MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY && !isAttributeEnabled
        ) return PsiElementVisitor.EMPTY_VISITOR

        val attributes = hashMapOf<PsiElement, MutableMap<String, MutableList<AttributeUsage>>>()
        val reportedDuplicateAttributes = hashSetOf<PsiElement>()
        val reportedLayoutOwners = hashSetOf<PsiElement>()
        val reportedLayoutAttributes = hashSetOf<PsiElement>()
        val attributeContextResolver = HikageAttributeContextResolver.from(file.project)
        val androidAttributeResolver = if (issue == Issue.UNKNOWN_ATTRIBUTE) AndroidAttributeResolver.from(file) else null
        if (issue == Issue.UNKNOWN_ATTRIBUTE && androidAttributeResolver == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                if (!expression.isPotentialAttributeInspectionCall()) return
                val isPotentialSet = expression.isPotentialAttributeSet()
                val setCall = if (isPotentialSet) attributeContextResolver.resolveSetCall(expression) else null
                val method = setCall?.method ?: if (isPotentialSet) return else expression.resolveMethod() ?: return
                val isHikageSet = setCall != null
                if (isHikageSet && issue == Issue.DUPLICATE) {
                    holder.reportDuplicate(expression, attributes, reportedDuplicateAttributes)
                    return
                }
                if (isHikageSet && issue == Issue.UNKNOWN_ATTRIBUTE) {
                    if (setCall.hasInvalidAttributeName() || setCall.hasPreviousDuplicate(attributes)) return
                    holder.reportUnknownAttribute(setCall, attributeContextResolver, androidAttributeResolver ?: return)
                    return
                }
                if (isHikageSet && holder.reportInvalidAttribute(expression)) return

                if (isHikageSet && issue == Issue.INEFFECTIVE_LAYOUT_ATTRIBUTE) {
                    holder.reportIneffectiveLayoutAttributes(
                        expression,
                        attributeContextResolver,
                        reportedLayoutOwners,
                        reportedLayoutAttributes
                    )
                    return
                }

                when {
                    DeclarationMatcher.isHikageAttributeNamespaceFunction(method) -> {
                        holder.reportNamespaceShortcut(expression)
                        holder.reportTooLongNamespace(expression)
                    }
                    DeclarationMatcher.isHikageRootAttributeSetFunction(method) -> holder.reportRootSet(expression)
                    DeclarationMatcher.isHikageScopedAttributeSetFunction(method) -> holder.reportScopedSet(expression)
                }

                if (isHikageSet) holder.reportIdResource(expression)

                if (DeclarationMatcher.isHikagableFunction(method)) {
                    holder.reportMissingRuntimeAttributeDependency(expression, method)
                }
            }
        }
    }

    private fun KtCallExpression.isPotentialAttributeInspectionCall() = when (issue) {
        Issue.MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY -> hasPotentialAttributeArgument()
        Issue.INEFFECTIVE_LAYOUT_ATTRIBUTE -> isPotentialAttributeSet()
        Issue.NAMESPACE, Issue.TOO_LONG_STRING -> isPotentialAttributeSet() || isPotentialNamespace()
        else -> isPotentialAttributeSet()
    }

    private fun KtCallExpression.isPotentialAttributeSet() =
        calleeExpression?.text == HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME

    private fun KtCallExpression.isPotentialNamespace() = calleeExpression?.text == NAMESPACE_FUNCTION

    private fun KtCallExpression.hasPotentialAttributeArgument() = valueArguments.any { argument ->
        argument.getArgumentName()?.asName?.identifier == ATTRS_ARGUMENT
    } || valueArguments.getOrNull(2)?.getArgumentExpression().isPotentialAttributeExpression() ||
        lambdaArguments.any { argument ->
            argument.getLambdaExpression()?.hasPotentialAttributeEntryPoint() == true
        }

    private fun KtExpression?.isPotentialAttributeExpression() = this is KtLambdaExpression ||
        this is KtNameReferenceExpression || this is KtCallExpression

    private fun KtLambdaExpression.hasPotentialAttributeEntryPoint() = bodyExpression?.statements?.any { statement ->
        val calleeName = when (statement) {
            is KtCallExpression -> statement.calleeExpression?.text
            is KtQualifiedExpression -> (statement.selectorExpression as? KtCallExpression)?.calleeExpression?.text
            else -> null
        }
        when (calleeName) {
            HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME,
            NAMESPACE_FUNCTION,
            ANDROID_NAMESPACE,
            APP_NAMESPACE,
            HikageSymbols.HIKAGE_ATTRIBUTE_NAME -> true
            else -> false
        }
    } == true

    private fun ProblemsHolder.reportUnknownAttribute(
        setCall: HikageAttributeContextResolver.SetCall,
        contextResolver: HikageAttributeContextResolver,
        resolver: AndroidAttributeResolver
    ) {
        if (issue != Issue.UNKNOWN_ATTRIBUTE) return
        val attributeName = contextResolver.resolveAttributeName(setCall) ?: return
        val nameExpression = setCall.nameArgument?.getArgumentExpression() ?: return
        val scopes = if (attributeName.name.startsWith(LAYOUT_ATTRIBUTE_PREFIX))
            contextResolver.resolveScopes(setCall) ?: return
        else null

        when (resolver.resolve(attributeName.namespace, attributeName.name, scopes?.layout)) {
            AndroidAttributeResolver.Resolution.NotFound -> registerUnresolvedReference(
                nameExpression,
                "Cannot resolve attribute <code>${attributeName.qualifiedName}</code>"
            )
            else -> Unit
        }
    }

    private fun HikageAttributeContextResolver.SetCall.hasInvalidAttributeName(): Boolean {
        val attributeName = nameArgument?.getArgumentExpression()?.staticStringText() ?: return false
        return attributeName.invalidAttributeNameMessage() != null ||
            attributeName.attributeNameString().length > ATTRIBUTE_STRING_MAX_LENGTH
    }

    private fun HikageAttributeContextResolver.SetCall.hasPreviousDuplicate(
        attributes: MutableMap<PsiElement, MutableMap<String, MutableList<AttributeUsage>>>
    ): Boolean {
        val attributeName = nameArgument?.getArgumentExpression()?.staticStringText() ?: return false
        val attributeKey = attributeName.attributeKey() ?: return false
        val target = nameArgument.getArgumentExpression() ?: return false
        val root = expression.findAttributeRoot() ?: return false
        val usages = attributes.getOrPut(root) { hashMapOf() }.getOrPut(attributeKey) { mutableListOf() }
        val hasPreviousUsage = usages.any { usage -> usage.callExpression.canCoexistInExecutionPath(expression) }
        usages += AttributeUsage(expression, target)

        return hasPreviousUsage
    }

    private fun ProblemsHolder.reportMissingRuntimeAttributeDependency(call: KtCallExpression, method: PsiMethod) {
        if (issue != Issue.MISSING_RUNTIME_ATTRIBUTE_DEPENDENCY) return
        val argument = call.findArgument(method, ATTRS_ARGUMENT) ?: return
        val expression = argument.getArgumentExpression() ?: return
        val lambda = expression.resolveAttributeLambda() ?: return
        if (lambda.bodyExpression?.statements.isNullOrEmpty()) return

        val target = expression as? KtLambdaExpression
            ?: (argument.getArgumentName()?.referenceExpression ?: expression)
        registerProblem(
            target,
            "You must add the runtime attribute dependency to use this feature",
            ProblemHighlightType.GENERIC_ERROR,
            AddRuntimeAttributeDependencyFix(target)
        )
    }

    private fun ProblemsHolder.reportNamespaceShortcut(call: KtCallExpression) {
        if (issue != Issue.NAMESPACE) return
        val namespace = call.firstStringLiteralText() ?: return
        if (namespace != ANDROID_NAMESPACE && namespace != APP_NAMESPACE) return
        val replacement = call.namespaceShortcutReplacement(namespace) ?: return

        registerProblem(
            call,
            "Can be simplified to <code>$namespace</code>",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            ReplaceNamespaceShortcutFix(call, namespace, replacement)
        )
    }

    private fun ProblemsHolder.reportRootSet(call: KtCallExpression) {
        if (issue != Issue.MISSING_NAMESPACE) return
        val attributeName = call.firstStringLiteralText() ?: return
        if (attributeName.contains(':')) return
        val target = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return

        registerProblem(
            target,
            "Attribute <code>$attributeName</code> must include a namespace or be declared inside a namespace",
            ProblemHighlightType.GENERIC_ERROR,
            ReplaceExpressionFix(target, "Prefix with 'android:'", "\"$ANDROID_NAMESPACE:$attributeName\"")
        )
    }

    private fun ProblemsHolder.reportScopedSet(call: KtCallExpression) {
        if (issue != Issue.NAMESPACE) return
        val namespace = call.findAttributeNamespace() ?: return
        val attributeName = call.firstStringLiteralText() ?: return
        val separator = attributeName.indexOf(':')
        if (separator < 0) return

        val attributeNamespace = attributeName.substring(0, separator)
        val unprefixedName = attributeName.substring(separator + 1)
        if (attributeNamespace.isEmpty() || unprefixedName.isEmpty()) return
        val target = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return

        if (attributeNamespace == namespace) {
            registerProblem(
                target,
                "Attribute <code>$attributeName</code> is already inside the <code>$namespace</code> namespace",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                ReplaceExpressionFix(
                    target,
                    "Remove redundant '$namespace:' prefix",
                    "\"$unprefixedName\""
                )
            )
            return
        }

        registerProblem(
            target,
            "Attribute <code>$attributeName</code> uses the <code>$attributeNamespace</code> " +
                "namespace inside the <code>$namespace</code> namespace",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        )
    }

    private fun ProblemsHolder.reportTooLongNamespace(call: KtCallExpression) {
        if (issue != Issue.TOO_LONG_STRING) return
        val namespace = call.firstStringLiteralText() ?: return
        if (namespace.length <= ATTRIBUTE_STRING_MAX_LENGTH) return
        val target = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return

        registerProblem(
            target,
            "Attribute string is too long. Maximum length is $ATTRIBUTE_STRING_MAX_LENGTH characters",
            ProblemHighlightType.GENERIC_ERROR
        )
    }

    private fun ProblemsHolder.reportInvalidAttribute(call: KtCallExpression): Boolean {
        var hasError = false
        val attributeNameExpression = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return false
        val attributeName = call.stringLiteralTextAt(0)
        if (attributeName != null) {
            attributeName.invalidAttributeNameMessage()?.let { message ->
                hasError = true
                if (issue == Issue.INVALID_NAME)
                    registerProblem(attributeNameExpression, message, ProblemHighlightType.GENERIC_ERROR)
            }
            if (attributeName.attributeNameString().length > ATTRIBUTE_STRING_MAX_LENGTH) {
                hasError = true
                if (issue == Issue.TOO_LONG_STRING) registerProblem(
                    attributeNameExpression,
                    "Attribute string is too long. Maximum length is $ATTRIBUTE_STRING_MAX_LENGTH characters",
                    ProblemHighlightType.GENERIC_ERROR
                )
            }
        }

        val valueExpression = call.valueArguments.getOrNull(1)?.getArgumentExpression() ?: return hasError
        val value = valueExpression.staticStringText() ?: return hasError
        value.invalidResourceReferenceMessage()?.let { message ->
            hasError = true
            if (issue == Issue.INVALID_RESOURCE_REFERENCE)
                registerProblem(valueExpression, message, ProblemHighlightType.GENERIC_ERROR)
        }
        value.invalidColorValueMessage()?.let { message ->
            hasError = true
            if (issue == Issue.INVALID_COLOR_VALUE)
                registerProblem(valueExpression, message, ProblemHighlightType.GENERIC_ERROR)
        }
        if (value.length > ATTRIBUTE_STRING_MAX_LENGTH) {
            hasError = true
            if (issue == Issue.TOO_LONG_STRING) registerProblem(
                valueExpression,
                "Attribute string is too long. Maximum length is $ATTRIBUTE_STRING_MAX_LENGTH characters",
                ProblemHighlightType.GENERIC_ERROR
            )
        }

        return hasError
    }

    private fun ProblemsHolder.reportIdResource(call: KtCallExpression) {
        if (issue != Issue.CREATE_ID && issue != Issue.MISSING_ID) return
        val valueExpression = call.valueArguments.getOrNull(1)?.getArgumentExpression() ?: return
        val value = valueExpression.staticStringText() ?: return
        val idReference = value.idResourceReference() ?: return
        val idExists = valueExpression.hasIdResource(idReference.name)
        if (!idReference.createsId && idExists) return
        if (idReference.createsId && issue != Issue.CREATE_ID) return
        if (!idReference.createsId && issue != Issue.MISSING_ID) return

        val message = if (idReference.createsId)
            "Resource ID <code>${idReference.name}</code> cannot be created from Hikage attributes at runtime"
        else "Cannot resolve resource ID <code>${idReference.name}</code>"

        val fix = valueExpression.createIdResourceFix(idReference.name, idReference.createsId, idExists)
        if (!idReference.createsId) {
            if (fix == null) registerUnresolvedReference(valueExpression, message)
            else registerUnresolvedReference(valueExpression, message, fix)
            return
        }
        if (fix == null) registerProblem(valueExpression, message, ProblemHighlightType.GENERIC_ERROR)
        else registerProblem(valueExpression, message, ProblemHighlightType.GENERIC_ERROR, fix)
    }

    /**
     * Registers an unresolved Android symbol with the same native reference style as a missing Hikage layout ID.
     */
    private fun ProblemsHolder.registerUnresolvedReference(
        expression: KtExpression,
        message: String,
        vararg fixes: LocalQuickFix
    ) {
        val descriptor = ProblemDescriptorBase(
            expression,
            expression,
            message,
            fixes,
            ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
            false,
            null,
            true,
            isOnTheFly
        )
        descriptor.setTextAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES)
        registerProblem(descriptor)
    }

    private fun ProblemsHolder.reportIneffectiveLayoutAttributes(
        setCall: KtCallExpression,
        contextResolver: HikageAttributeContextResolver,
        reportedLayoutOwners: MutableSet<PsiElement>,
        reportedLayoutAttributes: MutableSet<PsiElement>
    ) {
        if (issue != Issue.INEFFECTIVE_LAYOUT_ATTRIBUTE) return
        val root = setCall.findAttributeRoot() ?: return
        val call = root.findOwnerCall() ?: return
        if (!reportedLayoutOwners.add(call)) return
        val method = call.resolveMethod() ?: return
        if (!DeclarationMatcher.isHikagableFunction(method)) return

        val lparamsArgument = call.findArgument(method, LPARAMS_ARGUMENT) ?: return
        val lparamsExpression = lparamsArgument.getArgumentExpression() ?: return
        if (lparamsExpression.text == "null") return

        val removeLparamsFix = lparamsArgument.createRemoveUnnecessaryLayoutParamsFix()

        val attrsExpression = call.findArgument(method, ATTRS_ARGUMENT)?.getArgumentExpression() ?: return
        val attributeLambda = (attrsExpression as? KtLambdaExpression) ?: attrsExpression.resolveAttributeLambda() ?: return
        val reports = attributeLambda.collectLayoutAttributeReports(contextResolver)
        if (reports.isEmpty()) return
        registerLayoutParamsConflict(lparamsArgument, removeLparamsFix)
        reportIneffectiveLayoutAttributes(reports, reportedLayoutAttributes)
    }

    private fun ProblemsHolder.reportDuplicate(
        call: KtCallExpression,
        attributes: MutableMap<PsiElement, MutableMap<String, MutableList<AttributeUsage>>>,
        reportedAttributes: MutableSet<PsiElement>
    ) {
        if (issue != Issue.DUPLICATE) return
        val attributeName = call.firstStringLiteralText() ?: return
        val attributeKey = attributeName.attributeKey() ?: return
        val target = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return
        val root = call.findAttributeRoot() ?: return
        val usages = attributes.getOrPut(root) { hashMapOf() }.getOrPut(attributeKey) { mutableListOf() }
        usages += AttributeUsage(call, target)
        val orderedUsages = usages.sortedBy { it.callExpression.textOffset }
        orderedUsages.forEachIndexed { index, usage ->
            val hasPreviousUsage = orderedUsages.subList(0, index).any { previous ->
                previous.callExpression.canCoexistInExecutionPath(usage.callExpression)
            }
            if (!hasPreviousUsage || !reportedAttributes.add(usage.target)) return@forEachIndexed

            registerProblem(
                usage.target,
                "Attribute <code>$attributeKey</code> is duplicated in the same attribute scope",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun ProblemsHolder.reportIneffectiveLayoutAttributes(
        reports: List<LayoutAttributeReport>,
        reportedLayoutAttributes: MutableSet<PsiElement>
    ) = reports.forEach { report ->
        if (!reportedLayoutAttributes.add(report.element)) return@forEach
        registerProblem(
            report.element,
            "Attribute <code>${report.name}</code> has no effect because <code>lparams</code> is specified",
            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
            RemoveIneffectiveLayoutAttributeFix(report.element)
        )
    }

    private fun ProblemsHolder.registerLayoutParamsConflict(
        lparamsArgument: KtValueArgument,
        removeLparamsFix: LocalQuickFixOnPsiElement?
    ) {
        val message = "The <code>lparams</code> argument conflicts with attributes using the <code>layout_</code> prefix " +
            "and prevents them from taking effect"

        if (removeLparamsFix == null)
            registerProblem(lparamsArgument, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        else registerProblem(lparamsArgument, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, removeLparamsFix)
    }

    private fun KtValueArgument.createRemoveUnnecessaryLayoutParamsFix(): RemoveUnnecessaryLayoutParamsArgumentFix? {
        val call = getArgumentExpression() as? KtCallExpression ?: return null
        if (call.valueArguments.isNotEmpty() || call.lambdaArguments.isNotEmpty()) return null
        if (!DeclarationMatcher.isHikageLayoutParamsFunction(call.resolveMethod() ?: return null)) return null

        val argumentList = parent as? KtValueArgumentList ?: return null
        val index = argumentList.arguments.indexOf(this).takeIf { it >= 0 } ?: return null
        if (getArgumentName() == null && argumentList.arguments.drop(index + 1).any { it.getArgumentName() == null }) return null

        return RemoveUnnecessaryLayoutParamsArgumentFix(this)
    }

    private fun KtCallExpression.namespaceShortcutReplacement(namespace: String): String? {
        if (calleeExpression?.text != NAMESPACE_FUNCTION) return null
        if ((valueArgumentList?.arguments?.size ?: 0) != 1) return null

        val lambdaText = lambdaArguments.joinToString(" ") { it.text }.takeIf(String::isNotEmpty)
        return listOfNotNull(namespace, lambdaText).joinToString(" ")
    }

    private fun String.attributeKey(): String? {
        val separator = indexOf(':')
        if (separator < 0) return takeIf(String::isNotEmpty)

        val namespace = substring(0, separator)
        val name = substring(separator + 1)
        if (namespace.isEmpty() || name.isEmpty()) return null
        return name
    }

    private tailrec fun KtExpression.resolveAttributeLambda(
        visited: MutableSet<PsiElement> = hashSetOf()
    ): KtLambdaExpression? {
        if (!visited.add(this)) return null

        return when (this) {
            is KtLambdaExpression -> this
            is KtCallExpression -> attributeLambda()
            is KtNameReferenceExpression -> {
                val property = references.firstOrNull()?.resolve() as? KtProperty ?: return null
                if (!visited.add(property)) return null
                property.initializer?.resolveAttributeLambda(visited)
            }
            else -> null
        }
    }

    private fun KtCallExpression.attributeLambda(): KtLambdaExpression? {
        val method = resolveMethod() ?: return null
        if (!DeclarationMatcher.isHikageAttributeFactoryFunction(method)) return null

        return lambdaArguments.lastOrNull()?.getLambdaExpression()
    }

    private fun KtLambdaExpression.collectLayoutAttributeReports(
        contextResolver: HikageAttributeContextResolver
    ): List<LayoutAttributeReport> {
        val reports = mutableListOf<LayoutAttributeReport>()

        fun visit(element: PsiElement) {
            val call = element as? KtCallExpression
            val setCall = call
                ?.takeIf { expression -> expression.isPotentialAttributeSet() }
                ?.let { expression -> contextResolver.resolveSetCall(expression) }
            val attributeName = setCall?.expression?.firstStringLiteralText()
            val attributeKey = attributeName?.attributeKey()
            if (attributeKey?.startsWith(LAYOUT_ATTRIBUTE_PREFIX) == true)
                reports += LayoutAttributeReport(attributeKey, call.qualifiedCallExpression())

            element.children.forEach(::visit)
        }

        visit(this)
        return reports
    }

    private fun KtCallExpression.qualifiedCallExpression() = (parent as? KtQualifiedExpression)
        ?.takeIf { expression -> expression.selectorExpression === this }
        ?: this

    private fun KtCallExpression.findAttributeRoot() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtLambdaExpression>()
        .filterNot { it.isNamespaceLambda() }
        .firstOrNull { lambda ->
            val ownerCall = lambda.findOwnerCall()
            lambda.isAttrsArgument() ||
                ownerCall?.resolveMethod()?.let(DeclarationMatcher::isHikageAttributeFactoryFunction) == true
        }

    private fun KtLambdaExpression.isNamespaceLambda() = findOwnerCall()?.namespaceFromBlockCall() != null

    private fun KtLambdaExpression.isAttrsArgument(): Boolean {
        val argument = generateSequence(parent) { it.parent }
            .takeWhile { it !is KtCallExpression }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return false
        val ownerCall = findOwnerCall() ?: return false
        val method = ownerCall.resolveMethod() ?: return false

        return DeclarationMatcher.isHikagableFunction(method) && ownerCall.findArgument(method, ATTRS_ARGUMENT) === argument
    }

    private fun KtLambdaExpression.findOwnerCall() = generateSequence(parent) { it.parent }
        .takeWhile { it !is KtLambdaExpression }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtCallExpression.findAttributeNamespace(): String? {
        namespaceFromReceiver()?.let { return it }
        return generateSequence(parent) { it.parent }
            .filterIsInstance<KtCallExpression>()
            .firstNotNullOfOrNull { it.namespaceFromBlockCall() }
    }

    private fun KtCallExpression.namespaceFromReceiver(): String? {
        val qualified = parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression != this) return null

        return when (val receiver = qualified.receiverExpression) {
            is KtCallExpression -> receiver.namespaceFromCall()
            is KtNameReferenceExpression -> receiver.namespaceFromShortcut()
            is KtQualifiedExpression -> (receiver.selectorExpression as? KtNameReferenceExpression)
                ?.namespaceFromShortcut()
            else -> null
        }
    }

    private fun KtCallExpression.namespaceFromBlockCall(): String? {
        if (lambdaArguments.isEmpty()) return null
        return namespaceFromCall()
    }

    private fun KtCallExpression.namespaceFromCall(): String? {
        val method = resolveMethod() ?: return null
        DeclarationMatcher.findHikageAttributeNamespace(method)?.let { return it }
        if (!DeclarationMatcher.isHikageAttributeNamespaceFunction(method)) return null

        return findArgument(method, NAME_ARGUMENT)?.getArgumentExpression()?.staticStringText()
    }

    private fun KtNameReferenceExpression.namespaceFromShortcut() =
        mainReference.resolve()?.let(DeclarationMatcher::findHikageAttributeNamespace)

    private fun KtCallExpression.firstStringLiteralText() = stringLiteralTextAt(0)

    private fun KtCallExpression.stringLiteralTextAt(index: Int) =
        valueArguments.getOrNull(index)?.getArgumentExpression()?.staticStringText()

    private fun KtExpression.staticStringText(visited: MutableSet<PsiElement> = hashSetOf()): String? {
        if (!visited.add(this)) return null

        if (this is KtStringTemplateExpression) {
            val text = text
            if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"') || text.startsWith("\"\"\"")) return null
            if (text.contains('$')) return null
            return text.substring(1, text.length - 1)
        }

        if (this is KtQualifiedExpression) {
            val receiverText = receiverExpression.staticStringText(visited) ?: return null
            val selector = selectorExpression as? KtCallExpression ?: return null
            if (selector.calleeExpression?.text != "repeat") return null
            val repeatCount = selector.valueArguments.singleOrNull()
                ?.getArgumentExpression()
                ?.text
                ?.parseIntLiteralOrNull()
                ?: return null
            if (repeatCount < 0) return null
            if (receiverText.isEmpty()) return ""

            val cappedCount = minOf(repeatCount, ATTRIBUTE_STRING_MAX_LENGTH / receiverText.length + 1)
            return receiverText.repeat(cappedCount)
        }

        if (this is KtNameReferenceExpression) {
            val property = references.firstOrNull()?.resolve() as? KtProperty ?: return null
            if (!visited.add(property)) return null
            return property.initializer?.staticStringText(visited)
        }

        return null
    }

    private fun String.attributeNameString(): String {
        val separator = indexOf(':')
        return if (separator in 0..<lastIndex) substring(separator + 1) else this
    }

    private fun String.invalidAttributeNameMessage(): String? {
        if (isEmpty()) return "Attribute name must not be empty"

        val separator = indexOf(':')
        if (separator < 0) return invalidResourceNameMessage("Attribute name")
        return when {
            separator == 0 -> "Attribute <code>$this</code> is missing a namespace before <code>:</code>"
            separator == lastIndex -> "Attribute <code>$this</code> is missing a name after <code>:</code>"
            indexOf(':', separator + 1) >= 0 -> "Attribute <code>$this</code> must not contain more than one <code>:</code>"
            !substring(0, separator).isValidResourceNamespace() -> "Attribute <code>$this</code> has an invalid namespace prefix"
            else -> substring(separator + 1).invalidResourceNameMessage("Attribute name")
        }
    }

    private fun String.invalidResourceReferenceMessage(): String? {
        if (startsWith('@')) return invalidResourceValueReferenceMessage()
        if (startsWith('?')) return invalidAttributeValueReferenceMessage()
        return null
    }

    private fun String.invalidResourceValueReferenceMessage(): String? {
        if (this == "@null") return null

        var body = removePrefix("@")
        if (body.isEmpty()) return "Resource reference <code>$this</code> is missing a resource type and name"

        val createsResource = body.startsWith('+')
        if (createsResource) body = body.drop(1)
        if (body.isEmpty()) return "Resource reference <code>$this</code> is missing a resource type and name"

        val reference = body.resourceReferenceBody(displayValue = this, requireType = true)
        reference.message?.let { return it }
        return if (createsResource && reference.type != ID_RESOURCE_TYPE)
            "Resource reference <code>$this</code> can only create ID resources"
        else null
    }

    private fun String.invalidAttributeValueReferenceMessage(): String? {
        val body = removePrefix("?")
        if (body.isEmpty()) return "Attribute reference <code>$this</code> is missing an attribute name"

        val reference = body.resourceReferenceBody(displayValue = this, requireType = false)
        reference.message?.let { return it.replace("Resource reference", "Attribute reference") }
        return if (reference.type != null && reference.type != ATTR_RESOURCE_TYPE)
            "Attribute reference <code>$this</code> must use the attr resource type"
        else null
    }

    private fun String.resourceReferenceBody(displayValue: String, requireType: Boolean): ResourceReferenceBody {
        var body = this
        val colon = body.indexOf(':')
        if (colon >= 0) {
            if (colon == 0) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> is missing a package name before <code>:</code>"
            )
            if (colon == body.lastIndex) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> is missing a resource type and name after <code>:</code>"
            )
            if (body.indexOf(':', colon + 1) >= 0) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> must not contain more than one <code>:</code>"
            )

            val packageName = body.substring(0, colon)
            if (!packageName.isValidResourceNamespace()) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> has an invalid package name"
            )
            body = body.substring(colon + 1)
        }

        val slash = body.indexOf('/')
        val type: String?
        val name: String
        if (slash < 0) {
            if (requireType) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> must include a resource type, for example <code>@string/name</code>"
            )
            type = null
            name = body
        } else {
            if (slash == 0) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> is missing a resource type before <code>/</code>"
            )
            if (slash == body.lastIndex) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> is missing a resource name after <code>/</code>"
            )
            if (body.indexOf('/', slash + 1) >= 0) return ResourceReferenceBody(
                message = "Resource reference <code>$displayValue</code> must not contain more than one <code>/</code>"
            )
            type = body.substring(0, slash)
            name = body.substring(slash + 1)
        }

        if (type != null && !type.isValidResourceTypeName()) return ResourceReferenceBody(
            type, name,
            message = "Resource reference <code>$displayValue</code> has an invalid resource type"
        )
        name.invalidResourceNameMessage("Resource reference name")?.let { message ->
            return ResourceReferenceBody(
                type, name,
                message.replace(
                    "Resource reference name",
                    "Resource reference <code>$displayValue</code> name"
                )
            )
        }

        return ResourceReferenceBody(type, name)
    }

    private fun String.invalidColorValueMessage(): String? {
        if (!startsWith('#') || COLOR_VALUE_REGEX.matches(this)) return null
        return "Color value <code>$this</code> must be #RGB, #ARGB, #RRGGBB or #AARRGGBB"
    }

    private fun String.invalidResourceNameMessage(label: String): String? {
        val normalized = replace('.', '_')
        return when {
            isEmpty() -> "$label must not be empty"
            startsWith('.') -> "$label <code>$this</code> must not start with <code>.</code>"
            !SourceVersion.isIdentifier(normalized) -> "$label <code>$this</code> is not a valid resource name"
            SourceVersion.isKeyword(normalized) -> "$label <code>$this</code> must not be a reserved Java keyword"
            else -> null
        }
    }

    private fun String.isValidResourceName() = invalidResourceNameMessage("Resource name") == null
    private fun String.isValidResourceTypeName() = isValidResourceName()
    private fun String.isValidResourceNamespace() = split('.').all { it.isValidResourceName() }

    private fun String.idResourceReference(): IdResourceReference? {
        val createsId = startsWith("@+$ID_RESOURCE_TYPE/")
        val referencesId = startsWith("@$ID_RESOURCE_TYPE/")
        if (!createsId && !referencesId) return null

        val prefix = if (createsId) "@+$ID_RESOURCE_TYPE/" else "@$ID_RESOURCE_TYPE/"
        val idName = removePrefix(prefix)
        if (idName.isEmpty()) return null
        return IdResourceReference(idName, createsId)
    }

    private fun KtExpression.hasIdResource(idName: String) = failOpen {
        val facet = AndroidFacet.getInstance(this) ?: return@failOpen false
        val manager = StudioResourceRepositoryManager.getInstance(facet)
        manager.appResources.hasResources(manager.namespace, ResourceType.ID, idName)
    } ?: false

    private fun KtExpression.createIdResourceFix(
        idName: String,
        createsId: Boolean,
        idExists: Boolean
    ): LocalQuickFixOnPsiElement? {
        if (this !is KtStringTemplateExpression) return null
        if (!idName.isValidResourceName()) return null
        if (createsId && idExists) return ReplaceExpressionFix(
            this,
            "Replace with '@$ID_RESOURCE_TYPE/$idName'",
            "\"@$ID_RESOURCE_TYPE/$idName\""
        )
        if (AndroidFacet.getInstance(this) == null) return null
        return DeclareIdResourceFix(this, idName, createsId)
    }

    private fun String.parseIntLiteralOrNull(): Int? {
        val value = replace("_", "")
        return if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toIntOrNull(16)
        else value.toIntOrNull()
    }

    private fun KtCallExpression.canCoexistInExecutionPath(other: KtCallExpression): Boolean {
        val otherAncestors = other.ancestorsWithSelf().toList()
        return ancestorsWithSelf()
            .filter { ancestor -> otherAncestors.any { it === ancestor } }
            .none { it.hasMutuallyExclusiveBranches(this, other) }
    }

    private fun PsiElement.hasMutuallyExclusiveBranches(first: PsiElement, second: PsiElement) = when (this) {
        is KtIfExpression -> {
            val firstBranch = branchContaining(first)
            val secondBranch = branchContaining(second)
            firstBranch != null && secondBranch != null && firstBranch != secondBranch
        }
        is KtWhenExpression -> {
            val firstBranch = entries.firstOrNull { it.isSelfOrAncestorOf(first) }
            val secondBranch = entries.firstOrNull { it.isSelfOrAncestorOf(second) }
            firstBranch != null && secondBranch != null && firstBranch != secondBranch
        }
        is KtBinaryExpression -> operationToken == KtTokens.ELVIS && run {
            val firstBranch = elvisBranchContaining(first)
            val secondBranch = elvisBranchContaining(second)
            firstBranch != null && secondBranch != null && firstBranch != secondBranch
        }
        else -> false
    }

    private fun KtIfExpression.branchContaining(element: PsiElement): KtExpression? {
        then?.takeIf { it.isSelfOrAncestorOf(element) }?.let { return it }
        `else`?.takeIf { it.isSelfOrAncestorOf(element) }?.let { return it }
        return null
    }

    private fun KtBinaryExpression.elvisBranchContaining(element: PsiElement): KtExpression? {
        left?.takeIf { it.isSelfOrAncestorOf(element) }?.let { return it }
        right?.takeIf { it.isSelfOrAncestorOf(element) }?.let { return it }
        return null
    }

    private fun PsiElement.ancestorsWithSelf() = generateSequence(this) { it.parent }
    private fun PsiElement.isSelfOrAncestorOf(element: PsiElement) = element.ancestorsWithSelf().any { it === this }

    private fun KtExpression.idsXmlTarget(): File? {
        val facet = AndroidFacet.getInstance(this) ?: return null
        val sourceProviders = SourceProviderManager.getInstance(facet)
        val resourceFolders = sourceProviders.sources.resDirectoryUrls
            .map(VfsUtilCore::urlToPath)
            .map(::File)
            .distinctBy(File::getAbsolutePath)

        return resourceFolders.firstOrNull { File(File(it, "values"), IDS_XML_FILE).exists() }
            ?: sourceProviders.mainIdeaSourceProvider
                ?.resDirectoryUrls
                ?.map(VfsUtilCore::urlToPath)
                ?.map(::File)
                ?.firstOrNull()
    }

    private fun File.declareIdResource(project: Project, idName: String) {
        val valuesDirectory = VfsUtil.createDirectories(File(this, "values").path)
        val idsFile = valuesDirectory.findChild(IDS_XML_FILE)
        val xmlFile = if (idsFile == null) {
            val directory = PsiManager.getInstance(project).findDirectory(valuesDirectory) ?: return
            val file = PsiFileFactory.getInstance(project)
                .createFileFromText(IDS_XML_FILE, XmlFileType.INSTANCE, "<resources />")
            directory.add(file) as? XmlFile ?: return
        } else PsiManager.getInstance(project).findFile(idsFile) as? XmlFile ?: return

        val resourcesTag = xmlFile.rootTag ?: return
        if (resourcesTag.subTags.any { tag ->
                tag.name == "item" &&
                    tag.getAttributeValue("name") == idName &&
                    tag.getAttributeValue("type") == ID_RESOURCE_TYPE
            }
        ) return

        val itemTag = resourcesTag.createChildTag("item", "", null, false).apply {
            setAttribute("name", idName)
            setAttribute("type", ID_RESOURCE_TYPE)
        }
        val addedTag = resourcesTag.addSubTag(itemTag, false)
        CodeStyleManager.getInstance(project).reformat(if (idsFile == null) xmlFile else addedTag)
    }

    private data class AttributeUsage(
        val callExpression: KtCallExpression,
        val target: PsiElement
    )

    private data class LayoutAttributeReport(
        val name: String,
        val element: PsiElement
    )

    private data class ResourceReferenceBody(
        val type: String? = null,
        val name: String = "",
        val message: String? = null
    )

    private data class IdResourceReference(
        val name: String,
        val createsId: Boolean
    )

    private class ReplaceExpressionFix(
        target: PsiElement,
        private val text: String,
        private val replacement: String
    ) : LocalQuickFixOnPsiElement(target) {

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            (startElement as? KtExpression)?.replace(KtPsiFactory(project).createExpression(replacement))
        }
    }

    private class RemoveUnnecessaryLayoutParamsArgumentFix(argument: KtValueArgument) : LocalQuickFixOnPsiElement(argument) {

        private val text = "Remove unnecessary 'lparams'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val argument = startElement as? KtValueArgument ?: return
            (argument.parent as? KtValueArgumentList)?.removeArgument(argument)
        }
    }

    private class RemoveIneffectiveLayoutAttributeFix(attribute: PsiElement) : LocalQuickFixOnPsiElement(attribute) {

        private val text = "Remove ineffective element"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            startElement.delete()
        }
    }

    private class AddRuntimeAttributeDependencyFix(target: PsiElement) : LocalQuickFixOnPsiElement(target) {

        private val text = "Add '${Coordinates.RUNTIME_ATTRIBUTE_ARTIFACT}' dependency"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val module = ModuleUtilCore.findModuleForPsiElement(startElement) ?: return
            HikageRuntimeAttributeGate.addRuntimeAttributeDependency(module)
        }

        override fun startInWriteAction() = false
        override fun getFileModifierForPreview(target: PsiFile) = null
    }

    private class ReplaceNamespaceShortcutFix(
        call: KtCallExpression,
        private val namespace: String,
        private val replacement: String
    ) : LocalQuickFixOnPsiElement(call) {

        private val text = "Replace with '$namespace'"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val call = startElement as? KtCallExpression ?: return
            val targetFile = file as? KtFile ?: return

            val psiFactory = KtPsiFactory(project)
            val importName = if (namespace == ANDROID_NAMESPACE)
                HikageSymbols.HIKAGE_ATTRIBUTE_ANDROID
            else HikageSymbols.HIKAGE_ATTRIBUTE_APP

            targetFile.addImport(psiFactory, importName)
            call.replace(psiFactory.createExpression(replacement))
        }
    }

    private inner class DeclareIdResourceFix(
        expression: KtStringTemplateExpression,
        private val idName: String,
        private val createsId: Boolean
    ) : LocalQuickFixOnPsiElement(expression) {

        private val text = "Declare '$idName' in $IDS_XML_FILE"

        override fun getFamilyName() = text
        override fun getText() = text

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val expression = startElement as? KtStringTemplateExpression ?: return
            expression.idsXmlTarget()?.declareIdResource(project, idName) ?: return
            if (createsId) expression.replace(
                KtPsiFactory(project).createExpression("\"@$ID_RESOURCE_TYPE/$idName\"")
            )
        }

        override fun getFileModifierForPreview(target: PsiFile) = null
    }
}