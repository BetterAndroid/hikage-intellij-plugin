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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.intellij.dsl.builder

import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName

/**
 * Builds source text for IDE-only Hikage performer declarations.
 */
object PerformerSourceBuilder {

    /** Marker used to identify IDE-only resolve stubs. */
    const val FILE_MARKER = "IDE-only Hikage K2 performer resolve stub."

    private const val VIEW_FUNCTION_ALIAS = "_View"
    private const val VIEW_GROUP_FUNCTION_ALIAS = "_ViewGroup"

    private val hikagableClass = HikageSymbols.HIKAGABLE_ANNOTATION.toClassName()
    private val hikageClass = HikageSymbols.HIKAGE.toClassName()
    private val layoutParamsClass = HikageSymbols.HIKAGE_LAYOUT_PARAMS.toClassName()
    private val viewGroupClass = AndroidSymbols.VIEW_GROUP.toClassName()
    private val viewGroupLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS.toClassName()
    private val performerClass = HikageSymbols.HIKAGE_PERFORMER.toClassName()
    private val hikageViewClass = HikageSymbols.HIKAGE_VIEW_LAMBDA.toClassName()
    private val hikagePerformerClass = HikageSymbols.HIKAGE_PERFORMER_LAMBDA.toClassName()
    private val hikageAttributeClass = HikageSymbols.HIKAGE_ATTRIBUTE.toClassName()
    private val viewFunction = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION.toMemberName()
    private val viewGroupFunction = HikageSymbols.HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION.toMemberName()

    /**
     * Creates Kotlin source text that mirrors the current Hikage KSP performer shape.
     * @param declaration the normalized performer declaration.
     * @return [String] generated source text consumed by Kotlin resolve extensions.
     */
    fun createSource(declaration: PerformerDeclaration): String {
        val viewClass = declaration.viewClass.toClassName()
        val viewTopLevelClass = viewClass.topLevelClass()
        val lparamsClass = declaration.spec.lparams?.toClassName()
        val lparamsTopLevelClass = lparamsClass?.topLevelClass()
        val hasPerformer = lparamsClass != null && declaration.spec.performer
        val performFunctionAlias = if (hasPerformer) VIEW_GROUP_FUNCTION_ALIAS else VIEW_FUNCTION_ALIAS

        return FileSpec.builder(declaration.generatedPackageName, declaration.functionName).apply {
            addFileComment(FILE_MARKER)
            addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("%S, %S, %S, %S", "unused", "FunctionName", "DEPRECATION", "CONFLICTING_OVERLOADS")
                    .build()
            )
            addAnnotation(
                AnnotationSpec.builder(JvmName::class)
                    .addMember("%S", "${declaration.functionName}Performer")
                    .build()
            )

            addImport(viewGroupClass.packageName, viewGroupClass.simpleName)
            addImport(hikageClass.packageName, hikageClass.simpleName)
            addAliasedImport(if (hasPerformer) viewGroupFunction else viewFunction, performFunctionAlias)

            // KotlinPoet does not automatically import the parent class required for nested
            // references such as MyScope.MyView or ViewGroup.LayoutParams. Keep this aligned with
            // the real Hikage KSP generator so K2 resolves nested class names the same way.
            viewTopLevelClass?.let { addImport(it.packageName, it.simpleName) }
            lparamsTopLevelClass?.let { addImport(it.packageName, it.simpleName) }

            // LayoutParams appears in several Android widgets. The real generator always aliases
            // nested LayoutParams classes with their typed simple name, so mirror that here instead
            // of inventing longer IDE-only aliases.
            lparamsClass?.let { addAliasedImport(it, it.typedSimpleName) }
            addAliasedImport(viewGroupLayoutParamsClass, viewGroupLayoutParamsClass.typedSimpleName)

            addFunction(createFunction(declaration, viewClass, lparamsClass, performFunctionAlias))
        }.build().toString()
    }

    private fun createFunction(
        declaration: PerformerDeclaration,
        viewClass: ClassName,
        lparamsClass: ClassName?,
        performFunctionAlias: String
    ) = FunSpec.builder(declaration.functionName).apply {
        val viewTypeName = viewClass.simpleName
        addKdoc(
            """
              Perform the [$viewTypeName] in the current [Hikage.Performer] scope.
              @see Hikage.Performer
              @return [$viewTypeName]
            """.trimIndent()
        )
        addAnnotation(hikagableClass)
        addModifiers(KModifier.INLINE)
        addTypeVariable(TypeVariableName.Companion("LP", viewGroupLayoutParamsClass).copy(reified = true))
        receiver(performerClass.parameterizedBy(TypeVariableName.Companion("LP")))
        addParameter(
            ParameterSpec.builder("lparams", layoutParamsClass.copy(nullable = true))
                .defaultValue("null")
                .build()
        )
        addParameter(
            ParameterSpec.builder("id", String::class.asTypeName().copy(nullable = true))
                .defaultValue("null")
                .build()
        )
        if (declaration.spec.attrs)
            addParameter(
                ParameterSpec.builder("attrs", hikageAttributeClass, KModifier.NOINLINE)
                    .defaultValue("{}")
                    .build()
            )
        if (declaration.spec.init)
            addParameter(
                ParameterSpec.builder("init", hikageViewClass.parameterizedBy(viewClass), KModifier.NOINLINE)
                    .defaultValue("{}")
                    .build()
            )
        lparamsClass?.takeIf { declaration.spec.performer }?.let {
            addParameter(
                ParameterSpec.builder("performer", hikagePerformerClass.parameterizedBy(it), KModifier.NOINLINE)
                    .defaultValue("{}")
                    .build()
            )
            addCode(createViewGroupStatement(declaration, performFunctionAlias, viewClass, it))
        } ?: addCode(createViewStatement(declaration, performFunctionAlias, viewClass))
        returns(viewClass)
    }.build()

    private fun createViewStatement(
        declaration: PerformerDeclaration,
        performFunctionAlias: String,
        viewClass: ClassName
    ) = CodeBlock.builder().apply {
        add("return %L(\n", performFunctionAlias)
        indent()
        addInitStatement(declaration, viewClass)
        unindent()
        add("\n)\n")
    }.build()

    private fun createViewGroupStatement(
        declaration: PerformerDeclaration,
        performFunctionAlias: String,
        viewClass: ClassName,
        lparamsClass: ClassName
    ) = CodeBlock.builder().apply {
        add("return %L(\n", performFunctionAlias)
        indent()
        addInitStatement(declaration, viewClass, lparamsClass)
        if (declaration.spec.performer) add(",\nperformer = performer")
        unindent()
        add("\n)\n")
    }.build()

    private fun CodeBlock.Builder.addInitStatement(
        declaration: PerformerDeclaration,
        viewClass: ClassName,
        lparamsClass: ClassName? = null
    ) {
        add("viewClass = %T::class,\n", viewClass)
        lparamsClass?.let { add("childLpClass = %T::class,\n", it) }
        add("factory = %L,\n", createViewConstructorStatement(viewClass))
        add("lparams = lparams,\n")
        add("id = id")
        if (declaration.spec.attrs) add(",\nattrs = attrs")
        if (declaration.spec.init) add(",\ninit = init")
    }

    private fun createViewConstructorStatement(viewClass: ClassName) =
        CodeBlock.of("{ context, attrs -> %T(context, attrs) }", viewClass)

    private fun String.toClassName(): ClassName {
        val packageName = packageName()
        val className = removePrefix("$packageName.")
        return ClassName(packageName, className)
    }

    private fun String.toMemberName() = MemberName(
        substringBeforeLast("."),
        substringAfterLast(".")
    )

    private fun String.packageName(): String {
        val parts = split(".")
        val classStartIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
            .takeIf { index -> index > 0 }
            ?: return substringBeforeLast(".")

        return parts.take(classStartIndex).joinToString(".")
    }

    private fun ClassName.topLevelClass() =
        simpleName.substringBefore(".")
            .takeIf { name -> name != simpleName }
            ?.let { name -> ClassName(packageName, name) }

    private val ClassName.typedSimpleName get() = simpleName.replace(".", "_")
}