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
 * This file is created by fankes on 2026/7/7.
 */
@file:Suppress("SameParameterValue")

package com.highcapable.hikage.intellij.model

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Fully qualified Hikage symbols mirrored by the IDE plugin.
 */
object Symbols {

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

    /** The simple companion function name creating Hikage layouts. */
    const val HIKAGE_CREATE_FUNCTION_NAME = "create"

    /** The companion function creating Hikage layouts. */
    const val HIKAGE_CREATE_FUNCTION = "$HIKAGE.$HIKAGE_CREATE_FUNCTION_NAME"

    /** The simple companion function name creating Hikage delegates. */
    const val HIKAGE_BUILD_FUNCTION_NAME = "build"

    /** The companion function creating Hikage delegates. */
    const val HIKAGE_BUILD_FUNCTION = "$HIKAGE.$HIKAGE_BUILD_FUNCTION_NAME"

    /** The package containing Hikage layout parameter builders. */
    const val HIKAGE_LAYOUT_PACKAGE = "$HIKAGE_PACKAGE.layout"

    /** The simple Hikage layout parameters type name. */
    const val HIKAGE_LAYOUT_PARAMS_NAME = "LayoutParams"

    /** The Hikage layout parameters type. */
    const val HIKAGE_LAYOUT_PARAMS = "$HIKAGE_LAYOUT_PACKAGE.$HIKAGE_LAYOUT_PARAMS_NAME"

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

    /** The generated performer package prefix used by the Hikage compiler. */
    const val HIKAGE_WIDGET_PACKAGE_PREFIX = "com.highcapable.hikage.widget"

    /** The Android View base type. */
    const val ANDROID_VIEW = "android.view.View"

    /** The Android ViewGroup base type. */
    const val ANDROID_VIEW_GROUP = "android.view.ViewGroup"

    /** The Android ViewGroup LayoutParams type. */
    const val ANDROID_VIEW_GROUP_LAYOUT_PARAMS = "$ANDROID_VIEW_GROUP.LayoutParams"

    /** The Android Context type. */
    const val ANDROID_CONTEXT = "android.content.Context"

    /** The Android AttributeSet type. */
    const val ANDROID_ATTRIBUTE_SET = "android.util.AttributeSet"

    /** The Kotlin root object type. */
    const val KOTLIN_ANY = "kotlin.Any"

    /** The JVM representation of Kotlin's root object type. */
    const val JAVA_LANG_OBJECT = "java.lang.Object"

    /** The class ID for the Hikage layout owner type. */
    val HIKAGE_CLASS_ID = ClassId.topLevel(FqName(HIKAGE))

    /** The class ID for the Hikage DSL performer receiver type. */
    val HIKAGE_PERFORMER_CLASS_ID = hikageNestedClassId(HIKAGE_PERFORMER)

    /** The class ID for the Hikage layout delegate type. */
    val HIKAGE_DELEGATE_CLASS_ID = hikageNestedClassId(HIKAGE_DELEGATE)

    /** The class ID for the annotation marking Hikage DSL component functions. */
    val HIKAGABLE_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName(HIKAGABLE_ANNOTATION))

    /** The class ID for the Hikage layout parameters type. */
    val HIKAGE_LAYOUT_PARAMS_CLASS_ID = ClassId.topLevel(FqName(HIKAGE_LAYOUT_PARAMS))

    /** The class ID for the Android View base type. */
    val ANDROID_VIEW_CLASS_ID = ClassId.topLevel(FqName(ANDROID_VIEW))

    /** The class ID for the Android ViewGroup base type. */
    val ANDROID_VIEW_GROUP_CLASS_ID = ClassId.topLevel(FqName(ANDROID_VIEW_GROUP))

    /** The class ID for the Android Context type. */
    val ANDROID_CONTEXT_CLASS_ID = ClassId.topLevel(FqName(ANDROID_CONTEXT))

    /** The class ID for the Android AttributeSet type. */
    val ANDROID_ATTRIBUTE_SET_CLASS_ID = ClassId.topLevel(FqName(ANDROID_ATTRIBUTE_SET))

    /** The callable ID for the function creating Hikage layouts and delegates. */
    val HIKAGABLE_CALLABLE_ID = topLevelCallableId(HIKAGABLE_FUNCTION)

    /** The callable ID for the companion function creating Hikage layouts. */
    val HIKAGE_CREATE_CALLABLE_ID = hikageMemberCallableId(HIKAGE_CREATE_FUNCTION)

    /** The callable ID for the companion function creating Hikage delegates. */
    val HIKAGE_BUILD_CALLABLE_ID = hikageMemberCallableId(HIKAGE_BUILD_FUNCTION)

    private fun hikageNestedClassId(classFqName: String) =
        HIKAGE_CLASS_ID.createNestedClassId(Name.identifier(classFqName.removePrefix("$HIKAGE.")))

    private fun topLevelCallableId(functionFqName: String) = CallableId(
        FqName(functionFqName.substringBeforeLast(".")),
        Name.identifier(functionFqName.substringAfterLast("."))
    )

    private fun hikageMemberCallableId(functionFqName: String) =
        CallableId(HIKAGE_CLASS_ID, Name.identifier(functionFqName.removePrefix("$HIKAGE.")))
}