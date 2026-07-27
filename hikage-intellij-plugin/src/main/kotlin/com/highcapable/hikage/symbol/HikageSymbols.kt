/*
 * Hikage - A real-time AndroID View runtime powered by Kotlin DSL.
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
@file:Suppress("SameParameterValue")

package com.highcapable.hikage.symbol

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Fully qualified Hikage symbols mirrored by the IDE plugin.
 */
object HikageSymbols {

    /** The package containing the Hikage layout owner type. */
    const val HIKAGE_PACKAGE = "com.highcapable.hikage.core"

    /** The simple Hikage layout owner type name. */
    const val HIKAGE_NAME = "Hikage"

    /** The Hikage layout owner type. */
    const val HIKAGE = "$HIKAGE_PACKAGE.$HIKAGE_NAME"

    /** The simple Hikage DSL performer receiver type name. */
    const val HIKAGE_PERFORMER_NAME = "Performer"

    /** The Hikage DSL performer receiver type. */
    const val HIKAGE_PERFORMER = "$HIKAGE.$HIKAGE_PERFORMER_NAME"

    /** The simple Hikage layout delegate type name. */
    const val HIKAGE_DELEGATE_NAME = "Delegate"

    /** The Hikage layout delegate type. */
    const val HIKAGE_DELEGATE = "$HIKAGE.$HIKAGE_DELEGATE_NAME"

    /** The simple Hikage view lookup function name. */
    const val HIKAGE_GET_FUNCTION_NAME = "get"

    /** The simple nullable Hikage view lookup function name. */
    const val HIKAGE_GET_OR_NULL_FUNCTION_NAME = "getOrNull"

    /** The simple typed Hikage root lookup function name. */
    const val HIKAGE_ROOT_FUNCTION_NAME = "root"

    /** The simple Hikage delegate creation function name. */
    const val HIKAGE_DELEGATE_CREATE_FUNCTION_NAME = "create"

    /** The simple Hikage delegate invocation function name. */
    const val HIKAGE_DELEGATE_INVOKE_FUNCTION_NAME = "invoke"

    /** The package containing the `Hikagable` annotation. */
    const val HIKAGABLE_ANNOTATION_PACKAGE = "com.highcapable.hikage.annotation"

    /** The simple annotation name used in Kotlin source. */
    const val HIKAGABLE_ANNOTATION_NAME = "Hikagable"

    /** The annotation marking Hikage DSL component functions. */
    const val HIKAGABLE_ANNOTATION = "$HIKAGABLE_ANNOTATION_PACKAGE.$HIKAGABLE_ANNOTATION_NAME"

    /** The simple annotation name declaring a Hikage view performer. */
    const val HIKAGE_VIEW_ANNOTATION_NAME = "HikageView"

    /** The annotation declaring a Hikage performer for an annotated view class. */
    const val HIKAGE_VIEW_ANNOTATION = "$HIKAGABLE_ANNOTATION_PACKAGE.$HIKAGE_VIEW_ANNOTATION_NAME"

    /** The simple annotation name declaring a Hikage view performer for another view class. */
    const val HIKAGE_VIEW_DECLARATION_ANNOTATION_NAME = "HikageViewDeclaration"

    /** The annotation declaring a Hikage performer through a declaration object. */
    const val HIKAGE_VIEW_DECLARATION_ANNOTATION = "$HIKAGABLE_ANNOTATION_PACKAGE.$HIKAGE_VIEW_DECLARATION_ANNOTATION_NAME"

    /** The package containing the `Hikagable` factory function. */
    const val HIKAGABLE_FUNCTION_PACKAGE = "com.highcapable.hikage.core.base"

    /** The simple `Hikagable` factory function name. */
    const val HIKAGABLE_FUNCTION_NAME = "Hikagable"

    /** The function creating Hikage layouts and delegates. */
    const val HIKAGABLE_FUNCTION = "$HIKAGABLE_FUNCTION_PACKAGE.$HIKAGABLE_FUNCTION_NAME"

    /** The JVM file class containing the `Hikagable` factory functions. */
    const val HIKAGABLE_UTILS_CLASS = "$HIKAGABLE_FUNCTION_PACKAGE.HikagableUtils"

    /** The simple companion function name creating Hikage layouts. */
    const val HIKAGE_CREATE_FUNCTION_NAME = "create"

    /** The companion function creating Hikage layouts. */
    const val HIKAGE_CREATE_FUNCTION = "$HIKAGE.$HIKAGE_CREATE_FUNCTION_NAME"

    /** The simple companion function name creating Hikage delegates. */
    const val HIKAGE_BUILD_FUNCTION_NAME = "build"

    /** The companion function creating Hikage delegates. */
    const val HIKAGE_BUILD_FUNCTION = "$HIKAGE.$HIKAGE_BUILD_FUNCTION_NAME"

    /** The package containing reusable Hikage layout builders. */
    const val HIKAGE_BUILDER_PACKAGE = "$HIKAGE_PACKAGE.builder"

    /** The reusable Hikage layout builder type. */
    const val HIKAGE_BUILDER = "$HIKAGE_BUILDER_PACKAGE.HikageBuilder"

    /** The simple function name creating a lazy Hikage layout. */
    const val HIKAGE_LAZY_FUNCTION_NAME = "lazyHikage"

    /** The lazy Hikage layout factory function. */
    const val HIKAGE_LAZY_FUNCTION = "$HIKAGE_BUILDER_PACKAGE.$HIKAGE_LAZY_FUNCTION_NAME"

    /** The JVM file class containing lazy Hikage layout factory functions. */
    const val HIKAGE_LAZY_UTILS_CLASS = "$HIKAGE_BUILDER_PACKAGE.LazyHikageUtils"

    /** The package containing Hikage layout parameter builders. */
    const val HIKAGE_LAYOUT_PACKAGE = "$HIKAGE_PACKAGE.layout"

    /** The context-aware operator that invokes a Hikage delegate or builder as a layout. */
    const val HIKAGE_LAYOUT_INVOKE_FUNCTION = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_DELEGATE_INVOKE_FUNCTION_NAME"

    /** The package containing Hikage layout scope extensions. */
    const val HIKAGE_LAYOUT_EXTENSION_PACKAGE = "$HIKAGE_LAYOUT_PACKAGE.extension"

    /** The Hikage function that inserts an existing layout or layout resource. */
    const val HIKAGE_LAYOUT_FUNCTION_NAME = "Layout"

    /** The simple Hikage layout parameters type name. */
    const val HIKAGE_LAYOUT_PARAMS_NAME = "LayoutParams"

    /** The Hikage layout parameters type. */
    const val HIKAGE_LAYOUT_PARAMS = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_LAYOUT_PARAMS_NAME"

    /** The simple internal Hikage performer context type name. */
    const val HIKAGE_PERFORM_CONTEXT_NAME = "PerformContext"

    /** The internal Hikage performer context type. */
    const val HIKAGE_PERFORM_CONTEXT = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_PERFORM_CONTEXT_NAME"

    /** The simple Hikage resources scope type name. */
    const val HIKAGE_RESOURCES_SCOPE_NAME = "ResourcesScope"

    /** The Hikage resources scope type. */
    const val HIKAGE_RESOURCES_SCOPE = "$HIKAGE_LAYOUT_EXTENSION_PACKAGE.$HIKAGE_RESOURCES_SCOPE_NAME"

    /** The simple Hikage base view performer function name. */
    const val HIKAGE_LAYOUT_VIEW_FUNCTION_NAME = "View"

    /** The Hikage base view performer function. */
    const val HIKAGE_LAYOUT_VIEW_FUNCTION = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_LAYOUT_VIEW_FUNCTION_NAME"

    /** The simple Hikage base view group performer function name. */
    const val HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION_NAME = "ViewGroup"

    /** The Hikage base view group performer function. */
    const val HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_LAYOUT_VIEW_GROUP_FUNCTION_NAME"

    /** The package containing Hikage base DSL lambdas. */
    const val HIKAGE_BASE_PACKAGE = "$HIKAGE_PACKAGE.base"

    /** The simple Hikage view initialization lambda type name. */
    const val HIKAGE_VIEW_LAMBDA_NAME = "HikageView"

    /** The Hikage view initialization lambda type. */
    const val HIKAGE_VIEW_LAMBDA = "$HIKAGE_BASE_PACKAGE.$HIKAGE_VIEW_LAMBDA_NAME"

    /** The simple Hikage child performer lambda type name. */
    const val HIKAGE_PERFORMER_LAMBDA_NAME = "HikagePerformer"

    /** The Hikage child performer lambda type. */
    const val HIKAGE_PERFORMER_LAMBDA = "$HIKAGE_BASE_PACKAGE.$HIKAGE_PERFORMER_LAMBDA_NAME"

    /** The package containing Hikage attribute lambdas. */
    const val HIKAGE_ATTRIBUTE_PACKAGE = "$HIKAGE_PACKAGE.attribute"

    /** The simple Hikage attribute lambda type name. */
    const val HIKAGE_ATTRIBUTE_NAME = "HikageAttribute"

    /** The Hikage attribute lambda type. */
    const val HIKAGE_ATTRIBUTE = "$HIKAGE_ATTRIBUTE_PACKAGE.$HIKAGE_ATTRIBUTE_NAME"

    /** The simple Hikage attribute namespace function name. */
    const val HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION_NAME = "namespace"

    /** The Hikage attribute namespace function. */
    const val HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION = "$HIKAGE_ATTRIBUTE_PACKAGE.$HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION_NAME"

    /** The simple Hikage attribute setter function name. */
    const val HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME = "set"

    /** The Hikage root attribute setter function. */
    const val HIKAGE_ATTRIBUTE_SET_FUNCTION = "$HIKAGE_ATTRIBUTE_PACKAGE.$HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME"

    /** The JVM file class containing Hikage root attribute functions. */
    const val HIKAGE_ATTRIBUTE_UTILS_CLASS = "$HIKAGE_ATTRIBUTE_PACKAGE.HikageAttributeUtils"

    /** The JVM file class containing Hikage attribute namespace shortcuts. */
    const val HIKAGE_ATTRIBUTE_NAMESPACE_UTILS_CLASS = "$HIKAGE_ATTRIBUTE_PACKAGE.HikageAttributeNamespaceUtils"

    /** The Hikage scoped attribute receiver type. */
    const val HIKAGE_ATTRIBUTE_SCOPE_CLASS = "$HIKAGE_ATTRIBUTE_PACKAGE.AttributeScope"

    /** The Hikage Android attribute namespace shortcut. */
    const val HIKAGE_ATTRIBUTE_ANDROID = "$HIKAGE_ATTRIBUTE_PACKAGE.android"

    /** The Hikage application attribute namespace shortcut. */
    const val HIKAGE_ATTRIBUTE_APP = "$HIKAGE_ATTRIBUTE_PACKAGE.app"

    /** The package containing the optional Hikage runtime attribute implementation. */
    const val HIKAGE_RUNTIME_ATTRIBUTE_PACKAGE = "com.highcapable.hikage.runtime.attribute"

    /** The runtime attribute resolver supplied by the optional dependency. */
    const val HIKAGE_RUNTIME_ATTRIBUTE_RESOLVER = "$HIKAGE_RUNTIME_ATTRIBUTE_PACKAGE.AttributeSetResolver"

    /** The generated performer package prefix used by the Hikage compiler. */
    const val HIKAGE_WIDGET_PACKAGE_PREFIX = "com.highcapable.hikage.widget"

    /** The class ID for the Hikage layout owner type. */
    val HIKAGE_CLASS_ID = ClassId.topLevel(FqName(HIKAGE))

    /** The class ID for the Hikage DSL performer receiver type. */
    val HIKAGE_PERFORMER_CLASS_ID = hikageNestedClassId(HIKAGE_PERFORMER)

    /** The class ID for the Hikage layout delegate type. */
    val HIKAGE_DELEGATE_CLASS_ID = hikageNestedClassId(HIKAGE_DELEGATE)

    /** The class ID for the reusable Hikage layout builder type. */
    val HIKAGE_BUILDER_CLASS_ID = ClassId.topLevel(FqName(HIKAGE_BUILDER))

    /** The class ID for the annotation marking Hikage DSL component functions. */
    val HIKAGABLE_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName(HIKAGABLE_ANNOTATION))

    /** The class ID for the Hikage layout parameters type. */
    val HIKAGE_LAYOUT_PARAMS_CLASS_ID = ClassId.topLevel(FqName(HIKAGE_LAYOUT_PARAMS))

    /** The class ID for the Hikage resources scope type. */
    val HIKAGE_RESOURCES_SCOPE_CLASS_ID = ClassId.topLevel(FqName(HIKAGE_RESOURCES_SCOPE))

    /** The class ID for the Hikage attribute setter scope type. */
    val HIKAGE_ATTRIBUTE_SCOPE_CLASS_ID = ClassId.topLevel(FqName(HIKAGE_ATTRIBUTE_SCOPE_CLASS))

    /** The callable ID for the function creating Hikage layouts and delegates. */
    val HIKAGABLE_CALLABLE_ID = topLevelCallableId(HIKAGABLE_FUNCTION)

    /** The callable ID for the companion function creating Hikage layouts. */
    val HIKAGE_CREATE_CALLABLE_ID = hikageMemberCallableId(HIKAGE_CREATE_FUNCTION)

    /** The callable ID for the companion function creating Hikage delegates. */
    val HIKAGE_BUILD_CALLABLE_ID = hikageMemberCallableId(HIKAGE_BUILD_FUNCTION)

    /** The callable IDs for colored Hikage attribute factories and namespace functions. */
    val HIKAGE_ATTRIBUTE_CALLABLE_IDS = setOf(
        topLevelCallableId(HIKAGE_ATTRIBUTE),
        topLevelCallableId(HIKAGE_ATTRIBUTE_NAMESPACE_FUNCTION),
        topLevelCallableId(HIKAGE_ATTRIBUTE_ANDROID),
        topLevelCallableId(HIKAGE_ATTRIBUTE_APP)
    )

    /** The callable IDs for Hikage attribute setters that keep the default function color. */
    val HIKAGE_ATTRIBUTE_SET_CALLABLE_IDS = setOf(
        topLevelCallableId(HIKAGE_ATTRIBUTE_SET_FUNCTION),
        CallableId(HIKAGE_ATTRIBUTE_SCOPE_CLASS_ID, Name.identifier(HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME))
    )

    /** The callable IDs for the public and internal Hikage layout parameters builders. */
    val HIKAGE_LAYOUT_PARAMS_CALLABLE_IDS = setOf(
        topLevelCallableId(HIKAGE_LAYOUT_PARAMS),
        CallableId(
            ClassId.topLevel(FqName(HIKAGE_PERFORM_CONTEXT)),
            Name.identifier(HIKAGE_LAYOUT_PARAMS_NAME)
        )
    )

    private fun hikageNestedClassId(classFqName: String) =
        HIKAGE_CLASS_ID.createNestedClassId(Name.identifier(classFqName.removePrefix("$HIKAGE.")))

    private fun topLevelCallableId(functionFqName: String) = CallableId(
        FqName(functionFqName.substringBeforeLast(".")),
        Name.identifier(functionFqName.substringAfterLast("."))
    )

    private fun hikageMemberCallableId(functionFqName: String) = CallableId(
        HIKAGE_CLASS_ID,
        Name.identifier(functionFqName.removePrefix("$HIKAGE."))
    )
}