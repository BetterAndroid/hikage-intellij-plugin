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
 * This file is created by fankes on 2026/7/29.
 */
package com.highcapable.hikage.convert.resolver

import com.android.tools.idea.projectsystem.getModuleSystem
import com.highcapable.hikage.convert.bundle.ConversionBundle
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Kind
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Severity
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinLayoutAttribute
import com.highcapable.hikage.convert.model.KotlinLayoutCall
import com.highcapable.hikage.convert.model.KotlinLayoutCall.TypeReference
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Value
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.ViewConversionOption
import com.highcapable.hikage.convert.model.XmlLayout
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.convert.model.XmlLayoutNode
import com.highcapable.hikage.convert.planner.LayoutParamsPlanner
import com.highcapable.hikage.convert.planner.SpacingPlanner
import com.highcapable.hikage.convert.planner.ThemeAttributePlanner
import com.highcapable.hikage.convert.planner.ViewAttributePlanner
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.canonicalClassName
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.facet.findViewValidInXMLByName

/**
 * Resolves a neutral XML layout into wrapper-independent Hikage performer calls.
 */
object XmlLayoutModelResolver {

    private val ID_VALUE = "^@\\+?id/([A-Za-z_][A-Za-z0-9_]*)$".toRegex()

    /**
     * Resolves [layout] using the active Android and Hikage declaration snapshots.
     * @param layout the neutral XML layout.
     * @param facet the owning Android Facet.
     * @param declarations the current active Hikage performer declarations.
     * @param rootLayoutParamsClass the parent LayoutParams contract supplied by the output wrapper.
     * @param duplicateViewClasses the current conflicting performer View identities.
     * @param attributeResolver the Android Studio attribute model, or null when unavailable.
     * @param resourcePackageName the current module namespace used for local Android resource references.
     * @param viewAttributeOption the selected ordinary View-attribute option.
     * @param layoutParamsOption the selected layout-attribute option.
     * @return the output-ready call tree and ordered diagnostics.
     */
    fun resolve(
        layout: XmlLayout,
        facet: AndroidFacet,
        declarations: List<PerformerDeclaration>,
        rootLayoutParamsClass: String?,
        duplicateViewClasses: Set<String> = emptySet(),
        attributeResolver: AndroidAttributeResolver? = null,
        resourcePackageName: String? = facet.getModuleSystem().getPackageName(),
        viewAttributeOption: ViewConversionOption = ViewConversionOption.COMPATIBLE_MODE,
        layoutParamsOption: LayoutParamsConversionOption = LayoutParamsConversionOption.COMPATIBLE_MODE
    ): ConversionOutcome<KotlinLayoutNode> {
        val session = Session(
            facet = facet,
            declarations = declarations.associateBy(PerformerDeclaration::viewClass),
            duplicateViewClasses = duplicateViewClasses,
            attributeResolver = attributeResolver,
            resourcePackageName = resourcePackageName,
            viewAttributeOption = viewAttributeOption,
            layoutParamsOption = layoutParamsOption
        )
        val root = session.resolveNode(layout.root, rootLayoutParamsClass)
        return ConversionOutcome(root.takeUnless { session.hasErrors }, session.diagnostics)
    }

    private class Session(
        private val facet: AndroidFacet,
        private val declarations: Map<String, PerformerDeclaration>,
        private val duplicateViewClasses: Set<String>,
        private val attributeResolver: AndroidAttributeResolver?,
        private val resourcePackageName: String?,
        private val viewAttributeOption: ViewConversionOption,
        private val layoutParamsOption: LayoutParamsConversionOption
    ) {

        val diagnostics = mutableListOf<ConversionDiagnostic>()
        val hasErrors get() = diagnostics.any { diagnostic -> diagnostic.severity == Severity.ERROR }

        private val ids = mutableSetOf<String>()
        private val javaFacade = JavaPsiFacade.getInstance(facet.module.project)
        private val searchScope = GlobalSearchScope.allScope(facet.module.project)

        fun resolveNode(node: XmlLayoutNode, parentLayoutParamsClass: String?): KotlinLayoutNode? = when (node.kind) {
            XmlLayoutNode.Kind.VIEW -> resolveView(node, parentLayoutParamsClass)
            XmlLayoutNode.Kind.INCLUDE,
            XmlLayoutNode.Kind.MERGE -> node.fail(
                Kind.UNSUPPORTED_NODE,
                "conversion.diagnostic.unsupportedBatchNode",
                node.tagName
            )
            XmlLayoutNode.Kind.DATA_BINDING,
            XmlLayoutNode.Kind.DATA,
            XmlLayoutNode.Kind.TAG,
            XmlLayoutNode.Kind.REQUEST_FOCUS,
            XmlLayoutNode.Kind.FRAGMENT -> node.fail(
                Kind.UNSUPPORTED_NODE,
                "conversion.diagnostic.unsupportedNode",
                node.tagName
            )
        }

        private fun resolveView(node: XmlLayoutNode, parentLayoutParamsClass: String?): KotlinLayoutNode? {
            val viewClass = findViewValidInXMLByName(facet, node.rawClassName)
                ?: return node.fail(
                    Kind.UNKNOWN_VIEW,
                    "conversion.diagnostic.unknownView",
                    node.rawClassName
                )
            val viewClassName = viewClass.qualifiedName
                ?: return node.fail(
                    Kind.UNKNOWN_VIEW,
                    "conversion.diagnostic.unknownView",
                    node.rawClassName
                )
            val resolvedCall = when (viewClassName) {
                AndroidSymbols.VIEW_CLASS -> resolveBuiltInViewCall()
                AndroidSymbols.VIEW_GROUP_CLASS -> return node.fail(
                    Kind.MISSING_PERFORMER,
                    "conversion.diagnostic.missingPerformer",
                    viewClassName
                )
                else -> resolveDeclaredOrGenericCall(node, viewClass, viewClassName) ?: return null
            }

            val attributes = mutableListOf<KotlinLayoutAttribute>()
            val initializers = mutableListOf<KotlinLayoutInitializer>()
            val todoAttributes = mutableListOf<KotlinLayoutAttribute>()
            val todoComments = mutableListOf<String>()
            val layoutAttributes = node.attributes.filter { attribute ->
                attribute.kind == XmlLayoutAttribute.Kind.LAYOUT
            }
            val layoutParamsPlan = LayoutParamsPlanner.plan(
                attributes = layoutAttributes,
                option = layoutParamsOption,
                parentLayoutParamsClass = parentLayoutParamsClass,
                isMarginLayoutParams = parentLayoutParamsClass.isMarginLayoutParams(),
                memberInitializers = layoutAttributes.planLayoutParamsMembers(parentLayoutParamsClass)
            )
            val paddingPlan = if (viewAttributeOption != ViewConversionOption.FULLY_ATTRIBUTES)
                SpacingPlanner.planPadding(node.attributes.filter { attribute ->
                    attribute.kind == XmlLayoutAttribute.Kind.VIEW
                }) { attribute -> attribute.planPaddingRuntimeDimension() }
            else SpacingPlanner.Result(emptyList(), emptyList(), false)

            val paddingAttributes = paddingPlan.attributes.toSet()
            var isPaddingInitializerEmitted = false
            var id: String? = null

            node.attributes.forEach { attribute ->
                when (attribute.kind) {
                    XmlLayoutAttribute.Kind.METADATA -> Unit
                    XmlLayoutAttribute.Kind.ID -> {
                        val resolvedId = resolveId(attribute)
                        if (resolvedId == null) todoAttributes += attribute.toKotlinAttribute(attribute.normalizedNamespace())
                        else id = resolvedId
                    }
                    XmlLayoutAttribute.Kind.TOOLS -> diagnostics += attribute.diagnostic(
                        severity = Severity.INFORMATION,
                        kind = Kind.IGNORED_TOOLS_ATTRIBUTE,
                        key = "conversion.diagnostic.toolsIgnored",
                        attribute.qualifiedName
                    )
                    XmlLayoutAttribute.Kind.SPECIAL -> attribute.toTodo(
                        todoAttributes,
                        "conversion.diagnostic.specialAttribute"
                    )
                    XmlLayoutAttribute.Kind.VIEW -> when {
                        attribute !in paddingAttributes -> attribute.planViewAttribute(
                            viewClass = viewClass,
                            supportsAttrs = resolvedCall.supportsAttrs,
                            supportsInit = resolvedCall.supportsInit,
                            output = attributes,
                            initializers = initializers,
                            todos = todoAttributes
                        )
                        paddingPlan.isConverted && resolvedCall.supportsInit ->
                            if (!isPaddingInitializerEmitted) {
                                initializers += paddingPlan.initializers
                                isPaddingInitializerEmitted = true
                            }
                        viewAttributeOption == ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY -> attribute.toTodo(
                            todoAttributes,
                            if (paddingPlan.isConverted) "conversion.diagnostic.performerWithoutInit"
                            else "conversion.diagnostic.generateConstructorOnlyAttribute"
                        )
                        resolvedCall.supportsAttrs -> attribute.toOutputAttribute(attributes, todoAttributes)
                        else -> attribute.toTodo(todoAttributes, "conversion.diagnostic.performerWithoutAttrs")
                    }
                    XmlLayoutAttribute.Kind.LAYOUT -> when (attribute) {
                        in layoutParamsPlan.attributes ->
                            if (resolvedCall.supportsAttrs) attribute.toOutputAttribute(attributes, todoAttributes)
                            else attribute.toTodo(todoAttributes, "conversion.diagnostic.performerWithoutAttrs")
                        in layoutParamsPlan.todoAttributes ->
                            attribute.toTodo(todoAttributes, "conversion.diagnostic.layoutParamsOnlyAttribute")
                        else -> Unit
                    }
                }
            }

            val children = node.children.mapNotNull { child ->
                when (child.kind) {
                    XmlLayoutNode.Kind.TAG,
                    XmlLayoutNode.Kind.REQUEST_FOCUS -> {
                        val message = ConversionBundle.message("conversion.diagnostic.specialChild", child.tagName)
                        todoComments += message
                        diagnostics += ConversionDiagnostic(
                            severity = Severity.WARNING,
                            kind = Kind.UNSUPPORTED_NODE,
                            message = message,
                            source = child.source
                        )
                        null
                    }
                    else -> resolveNode(
                        child,
                        resolvedCall.childLayoutParamsClassName
                    )
                }
            }
            if (children.isNotEmpty() && !resolvedCall.call.hasChildPerformerParameter) return node.fail(
                Kind.UNSUPPORTED_HIERARCHY,
                "conversion.diagnostic.unsupportedHierarchy",
                resolvedCall.call.functionName
            )

            return KotlinLayoutNode(
                viewClassName = viewClassName,
                call = resolvedCall.call,
                layoutParams = layoutParamsPlan.layoutParams,
                id = id,
                attributes = attributes,
                initializers = initializers,
                todoAttributes = todoAttributes,
                todoComments = todoComments,
                children = children
            )
        }

        private fun PerformerDeclaration.toResolvedCall(): ResolvedCall {
            val hasChildPerformerParameter = declaration.isViewGroup && spec.performer
            return ResolvedCall(
                call = KotlinLayoutCall(
                    functionName = functionName,
                    importName = "$generatedPackageName.$functionName",
                    hasChildPerformerParameter = hasChildPerformerParameter
                ),
                supportsAttrs = spec.attrs,
                supportsInit = spec.init,
                childLayoutParamsClassName = spec.lparams.takeIf { hasChildPerformerParameter }
            )
        }

        private fun resolveBuiltInViewCall() = ResolvedCall(
            call = KotlinLayoutCall(
                functionName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION_NAME,
                importName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
                hasChildPerformerParameter = false
            ),
            supportsAttrs = true,
            supportsInit = true,
            childLayoutParamsClassName = null
        )

        private fun resolveDeclaredOrGenericCall(
            node: XmlLayoutNode,
            viewClass: PsiClass,
            viewClassName: String
        ): ResolvedCall? {
            if (viewClassName in duplicateViewClasses) return node.fail(
                Kind.DUPLICATE_PERFORMER,
                "conversion.diagnostic.duplicatePerformer",
                viewClassName
            )
            return declarations[viewClassName]?.toResolvedCall()
                ?: resolveGenericCall(node, viewClass, viewClassName)
        }

        private fun resolveGenericCall(
            node: XmlLayoutNode,
            viewClass: PsiClass,
            viewClassName: String
        ): ResolvedCall? {
            val viewBaseClass = javaFacade.findClass(AndroidSymbols.VIEW_CLASS, searchScope)
                ?: return node.fail(
                    Kind.MISSING_PERFORMER,
                    "conversion.diagnostic.missingPerformer",
                    viewClassName
                )
            if (viewClass.isInterface || viewClass.isEnum || viewClass.isAnnotationType ||
                viewClass.hasModifierProperty(PsiModifier.ABSTRACT) ||
                !viewClass.hasModifierProperty(PsiModifier.PUBLIC) ||
                (viewClass != viewBaseClass && !viewClass.isInheritor(viewBaseClass, true)) ||
                !viewClass.hasGenericViewConstructor()
            ) return node.fail(
                Kind.MISSING_PERFORMER,
                "conversion.diagnostic.missingPerformer",
                viewClassName
            )

            val viewReference = viewClass.toTypeReference() ?: return node.fail(
                Kind.MISSING_PERFORMER,
                "conversion.diagnostic.missingPerformer",
                viewClassName
            )
            val viewGroupClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP_CLASS, searchScope)
                ?: return node.fail(
                    Kind.MISSING_PERFORMER,
                    "conversion.diagnostic.missingPerformer",
                    viewClassName
                )
            val isViewGroup = viewClass == viewGroupClass || viewClass.isInheritor(viewGroupClass, true)
            val resolved = if (isViewGroup) {
                val childLayoutParamsClass = resolveChildLayoutParamsClass(viewClass)
                    ?: return node.fail(
                        Kind.MISSING_PERFORMER,
                        "conversion.diagnostic.missingPerformer",
                        viewClassName
                    )
                val childLayoutParamsClassName = childLayoutParamsClass.qualifiedName
                    ?: return node.fail(
                        Kind.MISSING_PERFORMER,
                        "conversion.diagnostic.missingPerformer",
                        viewClassName
                    )
                val childLayoutParamsReference = childLayoutParamsClass.toTypeReference()
                    ?: return node.fail(
                        Kind.MISSING_PERFORMER,
                        "conversion.diagnostic.missingPerformer",
                        viewClassName
                    )
                ResolvedCall(
                    call = KotlinLayoutCall(
                        functionName = HikageSymbols.HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION_NAME,
                        importName = HikageSymbols.HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION,
                        typeArguments = listOf(viewReference, childLayoutParamsReference),
                        hasChildPerformerParameter = true
                    ),
                    supportsAttrs = true,
                    supportsInit = true,
                    childLayoutParamsClassName = childLayoutParamsClassName
                )
            } else ResolvedCall(
                call = KotlinLayoutCall(
                    functionName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION_NAME,
                    importName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
                    typeArguments = listOf(viewReference),
                    hasChildPerformerParameter = false
                ),
                supportsAttrs = true,
                supportsInit = true,
                childLayoutParamsClassName = null
            )
            diagnostics += ConversionDiagnostic(
                severity = Severity.WARNING,
                kind = Kind.GENERIC_VIEW_FALLBACK,
                message = ConversionBundle.message("conversion.diagnostic.genericViewFallback", viewClassName),
                source = node.source
            )
            return resolved
        }

        private fun PsiClass.hasGenericViewConstructor() = constructors.any { constructor ->
            if (!constructor.hasModifierProperty(PsiModifier.PUBLIC)) return@any false
            constructor.parameterList.parameters.map { parameter -> parameter.type.canonicalClassName() } == listOf(
                AndroidSymbols.CONTEXT_CLASS,
                AndroidSymbols.ATTRIBUTE_SET_CLASS
            )
        }

        private fun resolveChildLayoutParamsClass(viewClass: PsiClass): PsiClass? {
            val layoutParamsClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS, searchScope) ?: return null
            var currentClass: PsiClass? = viewClass
            while (currentClass != null) {
                val factoryMethods = currentClass.methods.filter { method -> method.isLayoutParamsFactory() }
                if (factoryMethods.isNotEmpty()) {
                    val candidates = factoryMethods.mapNotNull { method ->
                        (method.returnType as? PsiClassType)?.resolve()?.takeIf { candidate ->
                            candidate == layoutParamsClass || candidate.isInheritor(layoutParamsClass, true)
                        }
                    }.distinctBy(PsiClass::getQualifiedName)
                    return candidates.singleOrNull() ?: candidates.singleOrNull { candidate ->
                        candidates.all { other -> candidate == other || candidate.isInheritor(other, true) }
                    }
                }
                currentClass = currentClass.superClass
            }
            return null
        }

        private fun PsiMethod.isLayoutParamsFactory(): Boolean {
            if (hasModifierProperty(PsiModifier.STATIC)) return false
            val parameters = parameterList.parameters
            return when (name) {
                "generateDefaultLayoutParams" -> parameters.isEmpty()
                "generateLayoutParams" -> parameters.singleOrNull()?.type?.canonicalClassName() in setOf(
                    AndroidSymbols.ATTRIBUTE_SET_CLASS,
                    AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
                )
                else -> false
            }
        }

        private fun PsiClass.toTypeReference(): TypeReference? {
            val classes = generateSequence(this) { declaration -> declaration.containingClass }
                .toList()
                .asReversed()
            if (classes.any { declaration -> !declaration.hasModifierProperty(PsiModifier.PUBLIC) }) return null
            val importName = classes.firstOrNull()?.qualifiedName ?: return null
            val name = classes.mapNotNull(PsiClass::getName).joinToString(".").takeIf(String::isNotBlank)
                ?: return null
            return TypeReference(name, importName)
        }

        private fun String?.isMarginLayoutParams(): Boolean {
            val className = this ?: return false
            val layoutParamsClass = javaFacade.findClass(className, searchScope) ?: return false
            val marginLayoutParamsClass = javaFacade.findClass(
                AndroidSymbols.VIEW_GROUP_MARGIN_LAYOUT_PARAMS_CLASS,
                searchScope
            ) ?: return false
            return layoutParamsClass == marginLayoutParamsClass ||
                layoutParamsClass.isInheritor(marginLayoutParamsClass, true)
        }

        private fun List<XmlLayoutAttribute>.planLayoutParamsMembers(
            parentLayoutParamsClass: String?
        ): Map<XmlLayoutAttribute, KotlinLayoutInitializer> {
            if (layoutParamsOption == LayoutParamsConversionOption.FULLY_ATTRIBUTES) return emptyMap()
            val className = parentLayoutParamsClass ?: return emptyMap()
            val layoutParamsClass = javaFacade.findClass(className, searchScope) ?: return emptyMap()

            return mapNotNull { attribute ->
                val namespace = attribute.normalizedNamespace() ?: return@mapNotNull null
                val resolution = attributeResolver?.resolve(namespace, attribute.localName)
                if (resolution == AndroidAttributeResolver.Resolution.NotFound) return@mapNotNull null
                val definition = (resolution as? AndroidAttributeResolver.Resolution.Found)?.attribute
                ViewAttributePlanner.plan(
                    attribute,
                    namespace,
                    layoutParamsClass,
                    definition,
                    resourcePackageName
                )?.let { initializer -> attribute to initializer }
            }.toMap()
        }

        private fun resolveId(attribute: XmlLayoutAttribute): String? {
            val id = ID_VALUE.matchEntire(attribute.value)?.groupValues?.get(1)
            if (id == null) {
                diagnostics += attribute.diagnostic(
                    severity = Severity.WARNING,
                    kind = Kind.INVALID_ID,
                    key = "conversion.diagnostic.invalidId",
                    attribute.rawValue
                )
                return null
            }
            if (!ids.add(id)) {
                diagnostics += attribute.diagnostic(
                    severity = Severity.ERROR,
                    kind = Kind.INVALID_ID,
                    key = "conversion.diagnostic.duplicateId",
                    id
                )
                return null
            }
            return id
        }

        private fun XmlLayoutAttribute.toOutputAttribute(
            output: MutableList<KotlinLayoutAttribute>,
            todos: MutableList<KotlinLayoutAttribute>
        ) {
            val namespace = normalizedNamespace()
            if (namespace == null) {
                toTodo(todos, "conversion.diagnostic.unresolvedNamespace")
                return
            }
            when (attributeResolver?.resolve(namespace, localName)) {
                AndroidAttributeResolver.Resolution.NotFound ->
                    toTodo(todos, "conversion.diagnostic.unknownAttribute")
                else -> output += toKotlinAttribute(namespace)
            }
        }

        private fun XmlLayoutAttribute.planViewAttribute(
            viewClass: PsiClass,
            supportsAttrs: Boolean,
            supportsInit: Boolean,
            output: MutableList<KotlinLayoutAttribute>,
            initializers: MutableList<KotlinLayoutInitializer>,
            todos: MutableList<KotlinLayoutAttribute>
        ) {
            val namespace = normalizedNamespace()
            if (namespace == null) {
                toTodo(todos, "conversion.diagnostic.unresolvedNamespace")
                return
            }
            val resolution = attributeResolver?.resolve(namespace, localName)
            if (resolution == AndroidAttributeResolver.Resolution.NotFound) {
                toTodo(todos, "conversion.diagnostic.unknownAttribute")
                return
            }
            val definition = (resolution as? AndroidAttributeResolver.Resolution.Found)?.attribute
            val themeAttributeDefinition = (attributeResolver?.resolveAttributeReference(value)
                as? AndroidAttributeResolver.Resolution.Found)?.attribute
            val initializer = takeUnless { viewAttributeOption == ViewConversionOption.FULLY_ATTRIBUTES }
                ?.let {
                    ViewAttributePlanner.plan(
                        attribute = this,
                        namespace = namespace,
                        viewClass = viewClass,
                        definition = definition,
                        resourcePackageName = resourcePackageName,
                        themeAttributeDefinition = themeAttributeDefinition
                    )
                }
            when {
                supportsInit && initializer != null -> initializers += initializer
                viewAttributeOption == ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY -> toTodo(
                    todos,
                    if (initializer != null) "conversion.diagnostic.performerWithoutInit"
                    else "conversion.diagnostic.generateConstructorOnlyAttribute"
                )
                supportsAttrs -> output += toKotlinAttribute(namespace)
                else -> toTodo(todos, "conversion.diagnostic.performerWithoutAttrs")
            }
        }

        private fun XmlLayoutAttribute.planPaddingRuntimeDimension(): Value? {
            val definition = (attributeResolver?.resolveAttributeReference(value)
                as? AndroidAttributeResolver.Resolution.Found)?.attribute ?: return null
            return ThemeAttributePlanner.planIntegerDimension(
                rawValue = value,
                definition = definition,
                currentModuleResourcePackageName = resourcePackageName
            )
        }

        private fun XmlLayoutAttribute.toTodo(
            todos: MutableList<KotlinLayoutAttribute>,
            key: String
        ) {
            todos += toKotlinAttribute(normalizedNamespace())
            diagnostics += diagnostic(
                severity = Severity.WARNING,
                kind = Kind.TODO_ATTRIBUTE,
                key = key,
                qualifiedName
            )
        }

        private fun XmlLayoutAttribute.toKotlinAttribute(namespace: String?) = KotlinLayoutAttribute(
            namespace = namespace,
            name = localName,
            qualifiedName = qualifiedName,
            value = value
        )

        private fun XmlLayoutAttribute.normalizedNamespace() = when {
            namespaceUri == AndroidSymbols.NAMESPACE_URI -> "android"
            namespaceUri == AndroidSymbols.AUTO_NAMESPACE_URI -> "app"
            namespaceUri.startsWith(AndroidSymbols.RESOURCE_NAMESPACE_URI_PREFIX) -> namespaceUri
                .removePrefix(AndroidSymbols.RESOURCE_NAMESPACE_URI_PREFIX)
                .takeIf(String::isNotBlank)
            namespaceUri.isEmpty() && namespacePrefix == "android" -> "android"
            namespaceUri.isEmpty() && namespacePrefix == "app" -> "app"
            else -> null
        }

        private fun XmlLayoutNode.fail(kind: Kind, key: String, vararg args: Any): Nothing? {
            diagnostics += ConversionDiagnostic(
                severity = Severity.ERROR,
                kind = kind,
                message = ConversionBundle.message(key, *args),
                source = source
            )
            return null
        }

        private fun XmlLayoutAttribute.diagnostic(
            severity: Severity,
            kind: Kind,
            key: String,
            vararg args: Any
        ) = ConversionDiagnostic(
            severity = severity,
            kind = kind,
            message = ConversionBundle.message(key, *args),
            source = source
        )
    }

    private data class ResolvedCall(
        val call: KotlinLayoutCall,
        val supportsAttrs: Boolean,
        val supportsInit: Boolean,
        val childLayoutParamsClassName: String?
    )
}