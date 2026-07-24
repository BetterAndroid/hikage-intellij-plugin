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
 * This file is created by fankes on 2026/7/21.
 */
package com.highcapable.hikage.mirror.lint.builder

import com.android.SdkConstants
import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.mirror.lint.model.LayoutSnapshot
import com.highcapable.hikage.mirror.lint.model.LayoutSnapshot.Attribute
import com.highcapable.hikage.mirror.lint.model.LayoutSnapshot.Node
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.util.concurrent.CancellationException

/**
 * Reconstructs XML-equivalent Android layout trees from resolved Kotlin performer calls.
 * @param file the Kotlin source file to analyze.
 * @param applicableElementNames the XML element names requested by the active Android Lint detectors.
 * @param applicableAttributeNames the XML attribute names requested by the active Android Lint detectors.
 * @param visitsAllAttributes whether any active detector requests every XML attribute.
 * @param isAttributeRuntimeEnabled whether runtime-backed attrs may be projected to synthetic XML.
 */
class LayoutSnapshotBuilder(
    private val file: KtFile,
    applicableElementNames: Set<String>,
    applicableAttributeNames: Set<String>,
    private val visitsAllAttributes: Boolean,
    private val isAttributeRuntimeEnabled: Boolean
) {

    private companion object {

        const val ATTRS_ARGUMENT = "attrs"
        const val INIT_ARGUMENT = "init"
        const val PERFORMER_ARGUMENT = "performer"
        const val DYNAMIC_VALUE = "@string/android_lint_dynamic"

        val SUPPORT_ATTRIBUTE_NAMES = setOf(
            SdkConstants.ATTR_FOCUSABLE,
            SdkConstants.ATTR_HINT,
            SdkConstants.ATTR_IMPORTANT_FOR_ACCESSIBILITY,
            SdkConstants.ATTR_TEXT
        )
    }

    private val project = file.project
    private val contextResolver by lazy { HikageAttributeContextResolver.from(project) }
    private val lintAttributeNames = applicableAttributeNames + SUPPORT_ATTRIBUTE_NAMES
    private val allScope = GlobalSearchScope.allScope(project)
    private val baseViewClass = JavaPsiFacade.getInstance(project).findClass(AndroidSymbols.VIEW_CLASS, allScope)
    private val applicableElements = applicableElementNames.mapNotNull { name ->
        name.resolveElementClass()?.let { psiClass -> ApplicableElement(name, psiClass) }
    }

    /**
     * Builds independent layout roots and links nested calls through their performer argument scopes.
     * @return [LayoutSnapshot]
     */
    fun build(): LayoutSnapshot {
        val nodes = PsiTreeUtil.collectElementsOfType(file, classOf<KtCallExpression>())
            .mapNotNull(::createNode)
            .associateBy(Node::call)
        nodes.values.forEach { node ->
            node.call.findParentNode(nodes)?.children?.add(node)
        }
        return LayoutSnapshot(nodes.values.filter { node -> node.call.findParentNode(nodes) == null })
    }

    private fun createNode(call: KtCallExpression): Node? {
        val method = call.resolveMethod() ?: return null
        if (!DeclarationMatcher.isHikagableFunction(method) || call.isInsideNonPerformerArgument()) return null
        val viewClass = call.resolveViewClass() ?: return null

        val attrs = if (isAttributeRuntimeEnabled) call.collectArgumentAttributes(method) else AttributeResult.EMPTY
        val attributes = attrs.attributes.associateBy(Attribute::name).values.toList()
        // Kotlin analysis already owns init and retained View mutations. Keep only their resolved attribute names to
        // prevent matching tag-level missing-attribute incidents; never project these mutations into synthetic XML.
        val nonAttrsAttributeNames = call.collectInitAttributeNames(method, viewClass) +
            call.collectPostInitAttributeNames(viewClass)

        return Node(
            call = call,
            element = call.calleeExpression ?: call,
            viewClass = viewClass,
            tagName = viewClass.findLintTagName(),
            attributes = attributes,
            isAttributeModelComplete = attrs.isComplete,
            nonAttrsAttributeNames = nonAttrsAttributeNames
        )
    }

    private fun KtCallExpression.collectArgumentAttributes(method: PsiMethod): AttributeResult {
        if (!method.hasParameter(ATTRS_ARGUMENT)) return AttributeResult.EMPTY
        findArgument(method, ATTRS_ARGUMENT)?.getArgumentExpression()?.let { expression ->
            return expression.collectAttributes(hashSetOf())
        }

        method.findSourceParameter(ATTRS_ARGUMENT)?.defaultValue?.let { expression ->
            return expression.collectAttributes(hashSetOf())
        }
        return AttributeResult(isComplete = method.isTrustedPerformer())
    }

    private fun KtExpression.collectAttributes(visiting: MutableSet<PsiElement>): AttributeResult {
        val expression = unwrapParentheses()
        if (!visiting.add(expression)) return AttributeResult(isComplete = false)

        return try {
            when (expression) {
                is KtLambdaExpression -> expression.collectAttrsLambda()
                is KtCallExpression -> expression.collectFactoryAttributes(visiting)
                is KtNameReferenceExpression -> expression.resolveReusableExpression()
                    ?.collectAttributes(visiting)
                    ?: AttributeResult(isComplete = false)
                else -> AttributeResult(isComplete = false)
            }
        } finally {
            visiting.remove(expression)
        }
    }

    private fun KtCallExpression.collectFactoryAttributes(visiting: MutableSet<PsiElement>): AttributeResult {
        val method = resolveMethod() ?: return AttributeResult(isComplete = false)
        if (!DeclarationMatcher.isHikageAttributeFactoryFunction(method)) return AttributeResult(isComplete = false)

        val lambda = valueArguments.firstNotNullOfOrNull(KtValueArgument::getArgumentExpression) as? KtLambdaExpression
            ?: lambdaArguments.singleOrNull()?.getLambdaExpression()
            ?: return AttributeResult.EMPTY
        return lambda.collectAttributes(visiting)
    }

    private fun KtLambdaExpression.collectAttrsLambda(): AttributeResult {
        val calls = PsiTreeUtil.collectElementsOfType(this, classOf<KtCallExpression>())
        val setCalls = calls.mapNotNull { call ->
            contextResolver.resolveSetCall(call)?.let { setCall -> call to setCall }
        }.toMap()
        var isComplete = !hasDynamicControlFlow()
        val attributes = mutableListOf<Attribute>()

        setCalls.values.forEach { setCall ->
            val name = contextResolver.resolveAttributeName(setCall)
            if (name == null) {
                isComplete = false
                return@forEach
            }
            if (name.namespace != "android" || name.name == SdkConstants.ATTR_ID) return@forEach
            if (!visitsAllAttributes && name.name !in lintAttributeNames) return@forEach

            val nameElement = setCall.nameArgument?.getArgumentExpression() ?: setCall.expression
            val valueElement = setCall.valueArgument?.getArgumentExpression()
            val staticValue = contextResolver.resolveAttributeValue(setCall)
            if (staticValue == null) isComplete = false

            attributes += Attribute(
                name = name.name,
                value = staticValue ?: name.name.failOpenDynamicValue(),
                nameElement = nameElement,
                valueElement = valueElement,
                isValueStatic = staticValue != null
            )
        }

        // Unknown calls may mutate the attribute state indirectly, so absence-based checks must fail open.
        calls.forEach { call ->
            if (call in setCalls || setCalls.keys.any { setCall -> PsiTreeUtil.isAncestor(setCall, call, true) }) return@forEach
            val method = call.resolveMethod()
            val isNamespace = method?.let(DeclarationMatcher::isHikageAttributeNamespaceFunction) == true ||
                method?.let(DeclarationMatcher::findHikageAttributeNamespace) != null
            if (!isNamespace && method?.let(DeclarationMatcher::isHikageAttributeFactoryFunction) != true) isComplete = false
        }

        return AttributeResult(attributes, isComplete)
    }

    private fun KtCallExpression.collectInitAttributeNames(method: PsiMethod, viewClass: PsiClass): Set<String> {
        if (!method.hasParameter(INIT_ARGUMENT)) return emptySet()
        val expression = findArgument(method, INIT_ARGUMENT)?.getArgumentExpression() ?: return emptySet()
        return expression.collectViewAttributeNames(viewClass, hashSetOf())
    }

    private fun KtExpression.collectViewAttributeNames(viewClass: PsiClass, visiting: MutableSet<PsiElement>): Set<String> {
        val expression = unwrapParentheses()
        if (!visiting.add(expression)) return emptySet()

        return try {
            when (expression) {
                is KtLambdaExpression -> expression.bodyExpression?.statements.orEmpty()
                    .mapNotNull { statement -> statement.resolveViewAttributeName(viewClass) }
                    .toSet()
                is KtNameReferenceExpression -> expression.resolveReusableExpression()
                    ?.collectViewAttributeNames(viewClass, visiting)
                    .orEmpty()
                else -> emptySet()
            }
        } finally {
            visiting.remove(expression)
        }
    }

    private fun KtExpression.resolveViewAttributeName(viewClass: PsiClass) = when (this) {
        is KtBinaryExpression -> resolveViewAssignmentName(viewClass)
        is KtCallExpression -> resolveViewMethodName(viewClass, hasExplicitReceiver = false)
        is KtQualifiedExpression -> when (val selector = selectorExpression) {
            is KtCallExpression -> selector.resolveViewMethodName(viewClass, receiverExpression !is KtThisExpression)
            is KtNameReferenceExpression -> resolveViewAssignmentName(viewClass)
            else -> null
        }
        else -> null
    }

    private fun KtExpression.resolveViewAssignmentName(viewClass: PsiClass): String? {
        val assignment = when (this) {
            is KtBinaryExpression -> this
            is KtQualifiedExpression -> parent as? KtBinaryExpression
            else -> null
        } ?: return null
        if (assignment.operationToken != KtTokens.EQ) return null

        val nameExpression = when (val target = assignment.left) {
            is KtNameReferenceExpression -> target
            is KtQualifiedExpression -> {
                if (target.receiverExpression !is KtThisExpression && target !== this) return null
                target.selectorExpression as? KtNameReferenceExpression ?: return null
            }
            else -> return null
        }
        return viewClass.findAttributeName(nameExpression.getReferencedName())
    }

    private fun KtCallExpression.resolveViewMethodName(viewClass: PsiClass, hasExplicitReceiver: Boolean): String? {
        if (hasExplicitReceiver) return null
        val method = resolveMethod() ?: return null
        if (!viewClass.inherits(method.containingClass)) return null
        return method.toAttributeName()
    }

    private fun KtCallExpression.collectPostInitAttributeNames(viewClass: PsiClass): Set<String> {
        val property = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtProperty>()
            .firstOrNull { candidate -> candidate.initializer?.directCallExpression() === this }
            ?: return emptySet()

        return PsiTreeUtil.collectElementsOfType(file, classOf<KtNameReferenceExpression>())
            .asSequence()
            .filter { reference -> reference.mainReference.resolve() === property }
            .mapNotNull { reference ->
                val qualified = reference.parent as? KtQualifiedExpression ?: return@mapNotNull null
                qualified.resolvePostInitAttributeName(viewClass)
            }
            .toSet()
    }

    private fun KtQualifiedExpression.resolvePostInitAttributeName(viewClass: PsiClass): String? {
        val selector = selectorExpression ?: return null
        if (selector is KtCallExpression) {
            val method = selector.resolveMethod() ?: return null
            if (!viewClass.inherits(method.containingClass)) return null
            return method.toAttributeName()
        }
        return takeIf { selector is KtNameReferenceExpression }?.resolveViewAssignmentName(viewClass)
    }

    private fun KtCallExpression.findParentNode(nodes: Map<KtCallExpression, Node>) = generateSequence(parent) { element -> element.parent }
        .filterIsInstance<KtCallExpression>()
        .firstNotNullOfOrNull { parentCall ->
            val parentNode = nodes[parentCall] ?: return@firstNotNullOfOrNull null
            val method = parentCall.resolveMethod() ?: return@firstNotNullOfOrNull null
            val performer = parentCall.findArgument(method, PERFORMER_ARGUMENT)?.getArgumentExpression()
                ?: return@firstNotNullOfOrNull null
            parentNode.takeIf { PsiTreeUtil.isAncestor(performer, this, false) }
        }

    private fun KtCallExpression.isInsideNonPerformerArgument() = generateSequence(parent) { element -> element.parent }
        .filterIsInstance<KtCallExpression>()
        .any { parentCall ->
            val method = parentCall.resolveMethod() ?: return@any false
            if (!DeclarationMatcher.isHikagableFunction(method)) return@any false
            listOf(ATTRS_ARGUMENT, INIT_ARGUMENT).any { argumentName ->
                parentCall.findArgument(method, argumentName)?.getArgumentExpression()?.let { argument ->
                    PsiTreeUtil.isAncestor(argument, this, false)
                } == true
            }
        }

    private fun KtCallExpression.resolveViewClass(): PsiClass? {
        val declaration = failOpen {
            analyze(this) { (expressionType as? KaClassType)?.symbol?.psi }
        } ?: return null
        val psiClass = when (declaration) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass()
            else -> null
        } ?: return null
        val viewClass = baseViewClass ?: return null

        return psiClass.takeIf { candidate -> candidate == viewClass || candidate.inherits(viewClass) }
    }

    private fun PsiClass.findLintTagName(): String {
        val chain = generateSequence(this) { current -> current.superClass }.toList()
        return applicableElements.mapNotNull { element ->
            chain.indexOfFirst { candidate -> candidate.qualifiedName == element.psiClass.qualifiedName }
                .takeIf { distance -> distance >= 0 }
                ?.let { distance -> distance to element.name }
        }.minByOrNull { (distance, _) -> distance }?.second
            ?: (qualifiedName ?: name ?: AndroidSymbols.VIEW_NAME).toXmlTagName()
    }

    private fun String.resolveElementClass(): PsiClass? {
        val facade = JavaPsiFacade.getInstance(project)
        if (contains('.')) return facade.findClass(this, allScope)

        return sequenceOf("android.widget.$this", "android.view.$this")
            .firstNotNullOfOrNull { qualifiedName -> facade.findClass(qualifiedName, allScope) }
    }

    private fun PsiClass.inherits(parent: PsiClass?) = parent != null &&
        (qualifiedName == parent.qualifiedName || failOpen { isInheritor(parent, true) } == true)

    private fun PsiClass.findAttributeName(propertyName: String): String? {
        val setterSuffix = propertyName.takeIf { name -> !name.startsWith("is") || name.getOrNull(2)?.isUpperCase() != true }
            ?.replaceFirstChar(Char::uppercaseChar)
            ?: propertyName.removePrefix("is")
        if (findMethodsByName("set$setterSuffix", true).none { method -> method.parameterList.parametersCount == 1 }) return null
        return setterSuffix.replaceFirstChar(Char::lowercaseChar)
    }

    private fun PsiMethod.toAttributeName(): String? {
        if (!name.startsWith("set") || name.length <= 3 || parameterList.parametersCount != 1) return null
        return name.removePrefix("set").replaceFirstChar(Char::lowercaseChar)
    }

    private fun PsiMethod.hasParameter(name: String) = findSourceParameter(name) != null ||
        parameterList.parameters.any { parameter -> parameter.name == name }

    private fun PsiMethod.findSourceParameter(name: String) = (navigationElement as? KtNamedFunction)
        ?.valueParameters
        ?.firstOrNull { parameter -> parameter.name == name }

    private fun PsiMethod.isTrustedPerformer(): Boolean {
        val owner = containingClass?.qualifiedName ?: return false
        return owner.startsWith(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX) ||
            owner.startsWith(HikageSymbols.HIKAGE_LAYOUT_PACKAGE)
    }

    private fun KtNameReferenceExpression.resolveReusableExpression() = (mainReference.resolve() as? KtProperty)?.initializer

    private fun KtExpression.unwrapParentheses(): KtExpression =
        if (this is KtParenthesizedExpression) expression?.unwrapParentheses() ?: this else this

    private fun KtExpression.directCallExpression() = when (this) {
        is KtCallExpression -> this
        is KtQualifiedExpression -> selectorExpression as? KtCallExpression
        else -> null
    }

    private fun KtLambdaExpression.hasDynamicControlFlow() =
        PsiTreeUtil.findChildOfAnyType(
            this,
            classOf<KtIfExpression>(),
            classOf<KtWhenExpression>(),
            classOf<KtLoopExpression>(),
            classOf<KtTryExpression>()
        ) != null

    private fun String.failOpenDynamicValue() = when (this) {
        SdkConstants.ATTR_CLICKABLE -> SdkConstants.VALUE_FALSE
        SdkConstants.ATTR_FOCUSABLE -> SdkConstants.VALUE_TRUE
        SdkConstants.ATTR_IMPORTANT_FOR_ACCESSIBILITY -> SdkConstants.VALUE_NO
        else -> DYNAMIC_VALUE
    }

    private fun String.toXmlTagName(): String {
        val normalized = replace('$', '_')
        if (normalized.isEmpty() || normalized.first().let { char -> !char.isLetter() && char != '_' })
            return AndroidSymbols.VIEW_NAME

        return normalized.takeIf { name -> name.all { char -> char.isLetterOrDigit() || char in ".-_" } }
            ?: AndroidSymbols.VIEW_NAME
    }

    private inline fun <T> failOpen(action: () -> T): T? = try {
        action()
    } catch (error: Exception) {
        if (error is ControlFlowException || error is CancellationException) throw error
        null
    }

    private data class ApplicableElement(
        val name: String,
        val psiClass: PsiClass
    )

    private data class AttributeResult(
        val attributes: List<Attribute> = emptyList(),
        val isComplete: Boolean
    ) {

        companion object {
            val EMPTY = AttributeResult(isComplete = true)
        }
    }
}