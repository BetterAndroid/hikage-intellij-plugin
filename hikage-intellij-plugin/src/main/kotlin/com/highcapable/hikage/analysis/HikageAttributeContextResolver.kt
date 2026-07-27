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
 * This file is created by fankes on 2026/7/19.
 */
package com.highcapable.hikage.analysis

import com.android.ide.common.rendering.api.ResourceReference
import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver.LayoutScope
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver.ViewScope
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.failOpen
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Resolves Hikage attribute setter calls, their namespace, and the View scope consuming their attrs block.
 */
class HikageAttributeContextResolver private constructor(private val project: Project) {

    companion object {

        private const val ATTRS_ARGUMENT = "attrs"
        private const val NAME_ARGUMENT = "name"
        private const val VALUE_ARGUMENT = "value"

        private const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"

        /**
         * Creates an attribute context resolver for the given [project].
         * @param project the project to resolve the attribute context for.
         * @return [HikageAttributeContextResolver]
         */
        fun from(project: Project) = HikageAttributeContextResolver(project)
    }

    private val searchScope = GlobalSearchScope.projectScope(project)

    private val baseViewClass by lazy(LazyThreadSafetyMode.NONE) {
        JavaPsiFacade.getInstance(project).findClass(AndroidSymbols.VIEW_CLASS, GlobalSearchScope.allScope(project))
    }
    private val baseViewGroupClass by lazy(LazyThreadSafetyMode.NONE) {
        JavaPsiFacade.getInstance(project).findClass(AndroidSymbols.VIEW_GROUP_CLASS, GlobalSearchScope.allScope(project))
    }

    /**
     * A resolved Hikage attribute setter and its source arguments.
     */
    data class SetCall(
        val expression: KtCallExpression,
        val nameArgument: KtValueArgument?,
        val valueArgument: KtValueArgument?,
        val namespace: String?
    )

    /**
     * A normalized Android attribute name.
     */
    data class AttributeName(
        val namespace: String,
        val name: String
    ) {

        /** The namespace-qualified name presented in diagnostics. */
        val qualifiedName get() = "$namespace:$name"
    }

    /**
     * The View and parent-layout scopes consuming a Hikage attribute value.
     */
    data class AttributeScopes(
        val view: ViewScope?,
        val layout: LayoutScope?
    )

    /**
     * An Android resource reference together with its declaration ownership.
     */
    data class ResolvedReference(
        val reference: ResourceReference,
        val isProjectResource: Boolean
    )

    private data class SetArguments(
        val name: KtValueArgument?,
        val value: KtValueArgument?
    )

    private data class AttributeConsumer(
        val viewClass: PsiClass,
        val layoutParentClass: PsiClass?
    )

    /** Resolves [expression] when it calls a real Hikage attribute setter. */
    fun resolveSetCall(expression: KtCallExpression): SetCall? {
        if (!HikageRuntimeAttributeGate.isEnabled(expression)) return null
        expression.resolveMethod()?.let { method -> return resolveSetCall(expression, method) }
        if (expression.calleeExpression?.text != HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME) return null

        val arguments = expression.resolveSetArguments() ?: return null

        return SetCall(
            expression,
            arguments.name,
            arguments.value,
            expression.findAttributeNamespace()
        )
    }

    /** Resolves the real Hikage attribute setter containing the string [expression]. */
    fun resolveSetCall(expression: KtStringTemplateExpression): SetCall? {
        val argument = generateSequence(expression.parent) { element -> element.parent }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return null
        val call = generateSequence(argument.parent) { element -> element.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null
        return resolveSetCall(call)?.takeIf { setCall ->
            argument === setCall.nameArgument || argument === setCall.valueArgument
        }
    }

    /** Resolves [expression] using its already resolved [method]. */
    fun resolveSetCall(expression: KtCallExpression, method: PsiMethod): SetCall? {
        if (!HikageRuntimeAttributeGate.isEnabled(expression)) return null
        if (!DeclarationMatcher.isHikageAttributeSetFunction(method)) return null

        return SetCall(
            expression,
            expression.findArgument(method, NAME_ARGUMENT),
            expression.findArgument(method, VALUE_ARGUMENT),
            expression.findAttributeNamespace()
        )
    }

    /** Resolves the static attribute name declared by [setCall]. */
    fun resolveAttributeName(setCall: SetCall): AttributeName? {
        val rawName = setCall.nameArgument
            ?.getArgumentExpression()
            ?.constantStringValue()
            ?: return null
        val separator = rawName.indexOf(':')
        if (separator >= 0) {
            if (setCall.namespace != null || separator == 0 || separator == rawName.lastIndex) return null
            if (rawName.indexOf(':', separator + 1) >= 0) return null
            return AttributeName(rawName.substring(0, separator), rawName.substring(separator + 1))
        }

        val namespace = setCall.namespace ?: return null
        return AttributeName(namespace, rawName)
    }

    /** Resolves the static value declared by [setCall]. */
    fun resolveAttributeValue(setCall: SetCall) = setCall.valueArgument
        ?.getArgumentExpression()
        ?.constantStringValue()

    /** Returns whether [expression] is an attribute name or Android resource value whose Rename is owned by Hikage. */
    fun isRenameCandidate(expression: KtStringTemplateExpression): Boolean {
        val setCall = resolveSetCall(expression) ?: return false
        if (!expression.isDirectStaticString()) return false
        if (setCall.nameArgument?.getArgumentExpression() === expression) return true
        if (setCall.valueArgument?.getArgumentExpression() !== expression) return false

        val value = resolveAttributeValue(setCall) ?: return false
        return value.startsWith('@') || value.startsWith('?')
    }

    /** Resolves the Android attribute-name or resource-value reference represented by [expression]. */
    fun resolveReference(expression: KtStringTemplateExpression) = resolveReferenceTarget(expression)?.reference

    /** Resolves the Android reference and whether its declaration belongs to the current project. */
    fun resolveReferenceTarget(expression: KtStringTemplateExpression) =
        resolveAttributeNameReference(expression) ?: resolveResourceReferenceTarget(expression)

    private fun resolveAttributeNameReference(expression: KtStringTemplateExpression): ResolvedReference? {
        val setCall = resolveSetCall(expression) ?: return null
        if (setCall.nameArgument?.getArgumentExpression() !== expression || !expression.isDirectStaticString()) return null

        val attributeName = resolveAttributeName(setCall) ?: return null
        val layoutScope = if (attributeName.name.startsWith(LAYOUT_ATTRIBUTE_PREFIX)) resolveScopes(setCall)?.layout
        else null
        val resolution = AndroidAttributeResolver.from(expression)?.resolve(
            attributeName.namespace,
            attributeName.name,
            layoutScope
        )
        val attribute = (resolution as? AndroidAttributeResolver.Resolution.Found)?.attribute ?: return null

        return ResolvedReference(attribute.definition.resourceReference, attribute.isProjectResource)
    }

    /** Resolves [expression] when it is a direct static resource value of a real Hikage attribute setter. */
    fun resolveResourceReference(expression: KtStringTemplateExpression) =
        resolveResourceReferenceTarget(expression)?.reference

    private fun resolveResourceReferenceTarget(expression: KtStringTemplateExpression): ResolvedReference? {
        val setCall = resolveSetCall(expression) ?: return null
        if (setCall.valueArgument?.getArgumentExpression() !== expression || !expression.isDirectStaticString()) return null

        val value = resolveAttributeValue(setCall) ?: return null
        val resolver = AndroidAttributeResolver.from(expression) ?: return null
        val reference = resolver.resolveResourceReference(value) ?: return null
        return ResolvedReference(reference, resolver.isProjectResource(reference))
    }

    /** Resolves the source text when [expression] is a direct static Android color value candidate. */
    fun resolveColorValue(expression: KtStringTemplateExpression): String? {
        val setCall = resolveSetCall(expression) ?: return null
        if (setCall.valueArgument?.getArgumentExpression() !== expression || !expression.isDirectStaticString()) return null

        return resolveAttributeValue(setCall)?.takeIf { value -> value.startsWith('#') }
    }

    /** Resolves the concrete or shared View and parent-layout scopes consuming [setCall]. */
    fun resolveScopes(setCall: SetCall): AttributeScopes? {
        val root = setCall.expression.findAttributeRoot() ?: return null
        val ownerCall = root.findOwnerCall()
        val ownerExpression = if (
            ownerCall?.resolveMethod()?.let(DeclarationMatcher::isHikageAttributeFactoryFunction) == true
        ) ownerCall
        else root

        ownerExpression.findAttrsConsumer()?.let { consumer ->
            val viewClass = consumer.resolveViewClass() ?: return null
            val layoutScope = consumer.resolveLayoutParentClass()?.let { parent -> LayoutScope(listOf(parent)) }
            return AttributeScopes(ViewScope(viewClass, listOf(viewClass)), layoutScope)
        }
        val declaration = ownerExpression.findReusableDeclaration() ?: return null

        return CachedValuesManager.getCachedValue(declaration) {
            CachedValueProvider.Result.create(
                declaration.resolveReusableScopes(),
                PsiModificationTracker.MODIFICATION_COUNT,
                ProjectRootModificationTracker.getInstance(project),
                DumbService.getInstance(project).modificationTracker
            )
        }
    }

    private fun KtCallExpression.findAttributeRoot() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtLambdaExpression>()
        .filterNot { lambda -> lambda.isNamespaceLambda() }
        .firstOrNull { lambda ->
            lambda.findAttrsConsumer() != null ||
                lambda.findOwnerCall()?.resolveMethod()?.let(DeclarationMatcher::isHikageAttributeFactoryFunction) == true ||
                lambda.isReusableInitializer()
        }

    private fun KtLambdaExpression.isNamespaceLambda() = findOwnerCall()?.namespaceFromBlockCall() != null

    private fun KtLambdaExpression.findOwnerCall() = generateSequence(parent) { it.parent }
        .takeWhile { it !is KtLambdaExpression }
        .filterIsInstance<KtCallExpression>()
        .firstOrNull()

    private fun KtExpression.findAttrsConsumer(): KtCallExpression? {
        val argument = generateSequence(parent) { it.parent }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return null
        val ownerCall = generateSequence(argument.parent) { it.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null
        val method = ownerCall.resolveMethod() ?: return null

        if (!DeclarationMatcher.isHikagableFunction(method)) return null
        if (ownerCall.findArgument(method, ATTRS_ARGUMENT) !== argument) return null
        if (argument.getArgumentExpression()?.isDirectReusableValue(this) != true) return null

        return ownerCall
    }

    private fun KtLambdaExpression.isReusableInitializer() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtCallableDeclaration>()
        .firstOrNull()
        ?.let { declaration ->
            declaration is KtProperty && declaration.initializer === this ||
                declaration is KtNamedFunction && declaration.bodyExpression === this
        } == true

    private fun KtExpression.findReusableDeclaration() = generateSequence(parent) { it.parent }
        .filterIsInstance<KtCallableDeclaration>()
        .firstOrNull { declaration ->
            val body = when (declaration) {
                is KtProperty -> declaration.initializer
                is KtNamedFunction -> declaration.bodyExpression
                else -> null
            }
            body?.isDirectReusableValue(this) == true
        }

    private fun KtExpression.isDirectReusableValue(usage: KtExpression) = this === usage || usage.hasDirectValueParent(this, usage)

    private tailrec fun PsiElement.hasDirectValueParent(root: KtExpression, usage: KtExpression): Boolean {
        val container = parent as? KtExpression ?: return false
        val isTransparent = when (container) {
            is KtCallExpression -> container.valueArguments.isEmpty() && container.lambdaArguments.isEmpty() &&
                container.calleeExpression?.let { callee -> PsiTreeUtil.isAncestor(callee, usage, false) } == true
            is KtQualifiedExpression -> container.selectorExpression?.let { selector ->
                PsiTreeUtil.isAncestor(selector, usage, false)
            } == true
            is KtParenthesizedExpression -> true
            else -> false
        }
        if (!isTransparent) return false
        if (container === root) return true

        return container.hasDirectValueParent(root, usage)
    }

    private fun KtCallableDeclaration.resolveReusableScopes(): AttributeScopes? {
        if (DumbService.isDumb(project)) return null

        val module = ModuleUtilCore.findModuleForPsiElement(this) ?: return null
        val consumers = resolveReusableConsumers(module, hashSetOf(), hashMapOf()) ?: return null
        val viewClasses = consumers.map(AttributeConsumer::viewClass).distinctBy(PsiClass::getQualifiedName)
        val commonViewClass = viewClasses.nearestCommonViewClass() ?: return null
        val layoutParentClasses = consumers.map(AttributeConsumer::layoutParentClass)
        val layoutScope = layoutParentClasses
            .takeIf { parents -> parents.none { parent -> parent == null } }
            ?.filterNotNull()
            ?.distinctBy(PsiClass::getQualifiedName)
            ?.let(::LayoutScope)

        return AttributeScopes(ViewScope(commonViewClass, viewClasses), layoutScope)
    }

    private fun KtCallableDeclaration.resolveReusableConsumers(
        module: Module,
        visiting: MutableSet<KtCallableDeclaration>,
        resolved: MutableMap<KtCallableDeclaration, List<AttributeConsumer>?>
    ): List<AttributeConsumer>? {
        if (resolved.containsKey(this)) return resolved[this]
        if (ModuleUtilCore.findModuleForPsiElement(this) != module || !visiting.add(this)) return null

        val consumers = try {
            collectReusableConsumers(module, visiting, resolved)
        } finally {
            visiting.remove(this)
        }
        resolved[this] = consumers

        return consumers
    }

    private fun KtCallableDeclaration.collectReusableConsumers(
        module: Module,
        visiting: MutableSet<KtCallableDeclaration>,
        resolved: MutableMap<KtCallableDeclaration, List<AttributeConsumer>?>
    ): List<AttributeConsumer>? {
        val references = ReferencesSearch.search(this, searchScope).findAll()
        if (references.isEmpty()) return null

        val consumers = mutableListOf<AttributeConsumer>()
        references.forEach { reference ->
            val usage = reference.element
            if (PsiTreeUtil.getParentOfType(usage, classOf<KDocElement>(), false) != null ||
                PsiTreeUtil.getParentOfType(usage, classOf<KtImportDirective>(), false) != null
            ) return@forEach

            val consumer = (usage as? KtExpression)?.findAttrsConsumer()
            if (consumer != null) {
                if (ModuleUtilCore.findModuleForPsiElement(consumer) != module) return null
                consumers += AttributeConsumer(
                    consumer.resolveViewClass() ?: return null,
                    consumer.resolveLayoutParentClass()
                )
                return@forEach
            }

            val alias = (usage as? KtExpression)?.findReusableDeclaration() ?: return null
            consumers += alias.resolveReusableConsumers(module, visiting, resolved) ?: return null
        }

        return consumers
    }

    private fun KtCallExpression.resolveViewClass(): PsiClass? {
        val declaration = failOpen {
            analyze(this) { (expressionType as? KaClassType)?.symbol?.psi }
        } ?: return null
        val targetClass = when (declaration) {
            is PsiClass -> declaration
            is KtClassOrObject -> declaration.toLightClass()
            else -> null
        } ?: return null

        return targetClass.takeIf { psiClass -> psiClass.isViewClass() }
    }

    private fun KtCallExpression.resolveLayoutParentClass(): PsiClass? {
        val layoutParamsClass = failOpen {
            analyze(this) {
                val candidates = this@resolveLayoutParentClass.resolveToCallCandidates()
                val bestCandidates = candidates.filter { candidate -> candidate.isInBestCandidates }
                    .ifEmpty { candidates }
                val functionCalls = bestCandidates.map { candidate ->
                    candidate.candidate as? KaFunctionCall<*> ?: return@analyze null
                }
                if (functionCalls.isEmpty() || functionCalls.any { functionCall ->
                        !DeclarationMatcher.isHikagableFunction(functionCall.signature.symbol)
                    }
                ) return@analyze null

                functionCalls.map { functionCall ->
                    val receiverType = functionCall.extensionReceiver?.type as? KaClassType
                        ?: return@analyze null
                    if (receiverType.classId != HikageSymbols.HIKAGE_PERFORMER_CLASS_ID) return@analyze null
                    val layoutParamsType = receiverType.typeArguments.singleOrNull()?.type as? KaClassType
                        ?: return@analyze null
                    when (val declaration = layoutParamsType.symbol.psi) {
                        is PsiClass -> declaration
                        is KtClassOrObject -> declaration.toLightClass()
                        else -> null
                    } ?: return@analyze null
                }.distinctBy(PsiClass::getQualifiedName).singleOrNull()
            }
        } ?: return null

        return generateSequence(layoutParamsClass) { current -> current.superClass }
            .mapNotNull(PsiClass::getContainingClass)
            .firstOrNull { candidate -> candidate.isViewGroupClass() }
    }

    private fun KtCallExpression.resolveSetArguments(): SetArguments? = failOpen {
        val sourceArguments = valueArgumentList?.arguments.orEmpty()
        analyze(this) {
            val candidates = this@resolveSetArguments.resolveToCallCandidates()
            val applicableCandidates = candidates.filter { candidate -> candidate.isInBestCandidates }
                .ifEmpty { candidates }
            val functionCalls = applicableCandidates.map { candidate ->
                candidate.candidate as? KaFunctionCall<*> ?: return@analyze null
            }
            if (functionCalls.isEmpty() || functionCalls.any { functionCall ->
                    !DeclarationMatcher.isHikageAttributeSetFunction(functionCall.signature.symbol)
                }
            ) return@analyze null

            functionCalls.map { functionCall ->
                val argumentsByName = buildMap {
                    functionCall.valueArgumentMapping.forEach { (argumentExpression, parameter) ->
                        sourceArguments.firstOrNull { argument ->
                            argument.getArgumentExpression() === argumentExpression
                        }?.let { argument -> put(parameter.name.asString(), argument) }
                    }
                }
                SetArguments(argumentsByName[NAME_ARGUMENT], argumentsByName[VALUE_ARGUMENT])
            }.distinct().singleOrNull()
        }
    }

    private fun PsiClass.isViewClass(): Boolean {
        val viewClass = baseViewClass ?: return false
        return this == viewClass || failOpen { isInheritor(viewClass, true) } == true
    }

    private fun PsiClass.isViewGroupClass(): Boolean {
        val viewGroupClass = baseViewGroupClass ?: return false
        return this == viewGroupClass || failOpen { isInheritor(viewGroupClass, true) } == true
    }

    private fun List<PsiClass>.nearestCommonViewClass(): PsiClass? {
        val first = firstOrNull() ?: return null
        return generateSequence(first) { current -> current.superClass }
            .filter { candidate -> candidate.isViewClass() }
            .firstOrNull { candidate ->
                all { view ->
                    view.qualifiedName == candidate.qualifiedName ||
                        failOpen { view.isInheritor(candidate, true) } == true
                }
            }
    }

    private fun KtCallExpression.findAttributeNamespace(): String? {
        namespaceFromReceiver()?.let { return it }
        val root = findAttributeRoot() ?: return null

        return generateSequence(parent) { it.parent }
            .takeWhile { element -> element !== root }
            .filterIsInstance<KtCallExpression>()
            .firstNotNullOfOrNull { call -> call.namespaceFromBlockCall() }
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

        return findArgument(method, NAME_ARGUMENT)?.getArgumentExpression()?.constantStringValue()
    }

    private fun KtNameReferenceExpression.namespaceFromShortcut() =
        mainReference.resolve()?.let(DeclarationMatcher::findHikageAttributeNamespace)

    private fun KtStringTemplateExpression.isDirectStaticString(): Boolean {
        val source = text
        return source.length >= 2 && source.startsWith('"') && source.endsWith('"') &&
            !source.startsWith("\"\"\"") && !source.contains('$') && !source.contains('\\')
    }

    private tailrec fun KtExpression.constantStringValue(visited: MutableSet<PsiElement> = hashSetOf()): String? {
        if (!visited.add(this)) return null
        if (this is KtStringTemplateExpression) {
            val source = text
            if (source.length < 2 || !source.startsWith('"') || !source.endsWith('"') ||
                source.startsWith("\"\"\"") || source.contains('$')
            ) return null
            return StringUtil.unescapeStringCharacters(source.substring(1, source.lastIndex))
        }
        if (this !is KtNameReferenceExpression) return null

        val property = mainReference.resolve() as? KtProperty ?: return null
        if (property.isVar) return null
        if (!visited.add(property)) return null

        return property.initializer?.constantStringValue(visited)
    }
}