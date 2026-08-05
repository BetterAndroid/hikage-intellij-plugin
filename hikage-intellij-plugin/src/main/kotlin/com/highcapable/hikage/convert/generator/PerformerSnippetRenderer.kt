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
package com.highcapable.hikage.convert.generator

import com.highcapable.hikage.convert.model.KotlinLayoutAttribute
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.MemberKind
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Value
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.KotlinLayoutParams
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.symbol.HikageSymbols
import com.squareup.kotlinpoet.CodeBlock

/**
 * Renders a complete performer-call hierarchy for insertion into an existing Performer body.
 */
object PerformerSnippetRenderer {

    /**
     * Renders [root] as a deterministic Kotlin Performer snippet.
     * @param root the wrapper-independent call tree.
     * @return the plain Kotlin source snippet and its deterministic symbol imports.
     */
    fun render(root: KotlinLayoutNode): KotlinSnippet {
        val imports = mutableSetOf<String>()
        val unqualifiedResourceClassNames = mutableSetOf<String>()
        root.collectSymbols(imports, unqualifiedResourceClassNames)
        return KotlinSnippet(
            code = CodeBlock.builder()
                .apply { renderNode(root) }
                .build()
                .toString()
                .withKotlinIndent()
                .trimEnd(),
            imports = imports.sorted(),
            unqualifiedResourceClassName = unqualifiedResourceClassNames.singleOrNull()
        )
    }

    private fun KotlinLayoutNode.collectSymbols(
        imports: MutableSet<String>,
        unqualifiedResourceClassNames: MutableSet<String>
    ) {
        imports += call.importName
        call.typeArguments.mapTo(imports) { reference -> reference.importName }
        if (layoutParams != null) imports += HikageSymbols.HIKAGE_LAYOUT_PARAMS

        attributes.mapTo(hashSetOf(), KotlinLayoutAttribute::namespace).forEach { namespace ->
            imports += when (namespace) {
                "android" -> HikageSymbols.HIKAGE_ATTRIBUTE_ANDROID
                "app" -> HikageSymbols.HIKAGE_ATTRIBUTE_APP
                else -> HikageSymbols.HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION
            }
        }
        layoutParams?.initializers?.forEach { initializer ->
            initializer.collectSymbols(imports, unqualifiedResourceClassNames)
        }
        initializers.forEach { initializer -> initializer.collectSymbols(imports, unqualifiedResourceClassNames) }
        children.forEach { child -> child.collectSymbols(imports, unqualifiedResourceClassNames) }
    }

    private fun KotlinLayoutInitializer.collectSymbols(
        imports: MutableSet<String>,
        unqualifiedResourceClassNames: MutableSet<String>
    ) {
        importName?.let(imports::add)
        arguments.forEach { argument -> argument.value.collectSymbols(imports, unqualifiedResourceClassNames) }
    }

    private fun Value.collectSymbols(
        imports: MutableSet<String>,
        unqualifiedResourceClassNames: MutableSet<String>
    ) {
        when (this) {
            is Value.Resource -> if (resourceClassName != "android.R") {
                imports += resourceClassName
                unqualifiedResourceClassNames += resourceClassName
            }
            is Value.ThemeAttribute -> {
                imports += importName
                if (resourceClassName != "android.R" && isCurrentModuleResource) {
                    imports += resourceClassName
                    unqualifiedResourceClassNames += resourceClassName
                }
            }
            is Value.SymbolicConstant -> imports += importName
            is Value.BitwiseOr -> values.mapTo(imports, Value.SymbolicConstant::importName)
            is Value.ExtensionCall -> {
                importName?.let(imports::add)
                receiver.collectSymbols(imports, unqualifiedResourceClassNames)
            }
            else -> Unit
        }
    }

    private fun CodeBlock.Builder.renderNode(node: KotlinLayoutNode) {
        add("%L", node.call.functionName)
        if (node.call.typeArguments.isNotEmpty()) {
            add("<")
            node.call.typeArguments.forEachIndexed { index, reference ->
                if (index > 0) add(", ")
                add("%L", reference.name)
            }
            add(">")
        }

        val hasTrailingInitializer = node.initializers.isNotEmpty() && !node.call.hasChildPerformerParameter
        val hasNamedInitializer = node.initializers.isNotEmpty() && node.call.hasChildPerformerParameter
        val hasArguments = node.layoutParams != null || node.id != null || node.attributes.isNotEmpty() ||
            hasNamedInitializer || node.todoAttributes.isNotEmpty() || node.todoComments.isNotEmpty()
        if (!hasArguments && !hasTrailingInitializer) add("()")
        if (hasArguments) {
            add("(\n")
            indent()
            val argumentCount = listOf(
                node.id != null,
                node.layoutParams != null,
                node.attributes.isNotEmpty(),
                hasNamedInitializer
            ).count { hasArgument -> hasArgument }

            var renderedArgumentCount = 0
            fun finishArgument() {
                renderedArgumentCount++
                add(if (renderedArgumentCount < argumentCount) ",\n" else "\n")
            }
            node.id?.let { id ->
                add("id = %S", id)
                finishArgument()
            }
            node.layoutParams?.let { layoutParams ->
                renderLayoutParams(layoutParams)
                finishArgument()
            }
            if (node.attributes.isNotEmpty()) {
                renderAttributes(node.attributes)
                finishArgument()
            }
            if (hasNamedInitializer) {
                add("init = ")
                renderInitializerLambda(node.initializers)
                finishArgument()
            }
            node.todoAttributes.forEach { attribute ->
                add("// TODO: Convert %L = %S manually.\n", attribute.qualifiedName, attribute.value)
            }
            node.todoComments.forEach { comment ->
                add("// TODO: %L\n", comment.toSingleLine())
            }
            unindent()
            add(")")
        }
        if (hasTrailingInitializer) {
            add(" ")
            renderInitializerLambda(node.initializers)
            return
        }
        if (node.children.isEmpty()) return

        add(" {\n")
        indent()
        node.children.forEach { child ->
            renderNode(child)
            add("\n")
        }
        unindent()
        add("}")
    }

    private fun CodeBlock.Builder.renderLayoutParams(layoutParams: KotlinLayoutParams) {
        val arguments = mutableListOf<Pair<String, CodeBlock>>()
        if (layoutParams.width == KotlinLayoutParams.Size.MatchParent &&
            layoutParams.height == KotlinLayoutParams.Size.MatchParent
        ) arguments += "matchParent" to CodeBlock.of("true")
        else {
            arguments.addSize("width", "widthMatchParent", layoutParams.width)
            arguments.addSize("height", "heightMatchParent", layoutParams.height)
        }
        add("lparams = LayoutParams")
        if (arguments.isNotEmpty() || layoutParams.initializers.isEmpty()) {
            add("(")
            arguments.forEachIndexed { index, (name, value) ->
                if (index > 0) add(", ")
                add("%L = %L", name, value)
            }
            add(")")
        }
        if (layoutParams.initializers.isNotEmpty()) {
            add(" ")
            renderInitializerLambda(layoutParams.initializers)
        }
    }

    private fun MutableList<Pair<String, CodeBlock>>.addSize(
        dimensionName: String,
        matchParentName: String,
        size: KotlinLayoutParams.Size
    ) = when (size) {
        KotlinLayoutParams.Size.MatchParent -> add(matchParentName to CodeBlock.of("true"))
        KotlinLayoutParams.Size.WrapContent -> Unit
        is KotlinLayoutParams.Size.Dp -> add(
            dimensionName to if (size.value == 0) CodeBlock.of("0") else CodeBlock.of("%L.dp", size.value)
        )
        is KotlinLayoutParams.Size.Px -> add(dimensionName to CodeBlock.of("%L", size.value))
    }

    private fun CodeBlock.Builder.renderAttributes(attributes: List<KotlinLayoutAttribute>) {
        add("attrs = {\n")
        indent()
        attributes.groupBy(KotlinLayoutAttribute::namespace).forEach { (namespace, scopedAttributes) ->
            when (namespace) {
                "android", "app" -> add("%L {\n", namespace)
                else -> add("namespace(%S) {\n", requireNotNull(namespace))
            }
            indent()
            scopedAttributes.forEach { attribute ->
                add("set(%S, %S)\n", attribute.name, attribute.value)
            }
            unindent()
            add("}\n")
        }
        unindent()
        add("}")
    }

    private fun CodeBlock.Builder.renderInitializerLambda(initializers: List<KotlinLayoutInitializer>) {
        add("{\n")
        indent()
        initializers.forEach { initializer ->
            when (initializer.memberKind) {
                MemberKind.PROPERTY -> {
                    val argument = initializer.arguments.single()
                    require(argument.name == null)
                    add("%N = ", initializer.memberName)
                    renderInitializerValue(argument.value)
                }
                MemberKind.METHOD -> {
                    add("%N(", initializer.memberName)
                    initializer.arguments.forEachIndexed { index, argument ->
                        if (index > 0) add(", ")
                        argument.name?.let { name -> add("%N = ", name) }
                        renderInitializerValue(argument.value)
                    }
                    add(")")
                }
            }
            add("\n")
        }
        unindent()
        add("}")
    }

    private fun CodeBlock.Builder.renderInitializerValue(value: Value) {
        when (value) {
            is Value.Text -> add("%S", value.value)
            is Value.BooleanLiteral -> add("%L", value.value)
            is Value.IntegerLiteral -> add("%L", value.value)
            is Value.SymbolicConstant -> renderSymbolicConstant(value)
            is Value.BitwiseOr -> value.values.forEachIndexed { index, constant ->
                if (index > 0) add(" or ")
                renderSymbolicConstant(constant)
            }
            is Value.FloatingPointLiteral -> add("%L%L", value.value, if (value.isFloat) "f" else "")
            is Value.Dp -> add("%L.dp", value.value)
            is Value.Resource -> {
                value.helperName?.let { helperName -> add("%N(", helperName) }
                if (value.resourceClassName == "android.R")
                    add("%N.%N.%N.%N", "android", "R", value.resourceType, value.resourceName)
                else add("%N.%N.%N", "R", value.resourceType, value.resourceName)
                if (value.helperName != null) add(")")
            }
            is Value.ThemeAttribute -> {
                add("%N.%N(", "context", value.functionName)
                when {
                    value.resourceClassName == "android.R" ->
                        add("%N.%N.%N.%N", "android", "R", "attr", value.resourceName)
                    value.isCurrentModuleResource -> add("%N.%N.%N", "R", "attr", value.resourceName)
                    else -> add("%L.%N.%N", value.resourceClassName, "attr", value.resourceName)
                }
                add(")")
            }
            is Value.ExtensionCall -> {
                renderInitializerValue(value.receiver)
                add(".%N()", value.functionName)
            }
            is Value.ReceiverProperty -> add("%N", value.memberName)
            Value.Null -> add("null")
        }
    }

    private fun CodeBlock.Builder.renderSymbolicConstant(value: Value.SymbolicConstant) {
        value.qualifier.split('.').forEachIndexed { index, className ->
            if (index > 0) add(".")
            add("%N", className)
        }
        add(".%N", value.memberName)
    }

    private fun String.toSingleLine() = replace('\r', ' ').replace('\n', ' ')

    private fun String.withKotlinIndent() = lineSequence().joinToString("\n") { line ->
        val kotlinPoetIndent = line.takeWhile { character -> character == ' ' }.length
        " ".repeat(kotlinPoetIndent * 2) + line.drop(kotlinPoetIndent)
    }
}