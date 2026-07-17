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
package com.highcapable.hikage.intellij.model

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Fully qualified Android symbols used by the IDE plugin.
 */
object AndroidSymbols {

    /** The Android `View` base type name. */
    const val VIEW_NAME = "View"

    /** The Android `View` base type. */
    const val VIEW_CLASS = "android.view.$VIEW_NAME"

    /** The Android `ViewGroup` base type name. */
    const val VIEW_GROUP_NAME = "ViewGroup"

    /** The Android `ViewGroup` base type. */
    const val VIEW_GROUP_CLASS = "android.view.$VIEW_GROUP_NAME"

    /** The Android `ViewGroup` LayoutParams type. */
    const val VIEW_GROUP_LAYOUT_PARAMS_CLASS = "$VIEW_GROUP_CLASS.LayoutParams"

    /** The Android `Context` type. */
    const val CONTEXT_CLASS = "android.content.Context"

    /** The Android `Resources` type. */
    const val RESOURCES_CLASS = "android.content.res.Resources"

    /** The Android `AttributeSet` type. */
    const val ATTRIBUTE_SET_CLASS = "android.util.AttributeSet"

    /** The AndroidX `ContextCompat` type. */
    const val CONTEXT_COMPAT_CLASS = "androidx.core.content.ContextCompat"

    /** The AndroidX `ResourcesCompat` type. */
    const val RESOURCES_COMPAT_CLASS = "androidx.core.content.res.ResourcesCompat"

    /** The class ID for the Android `View` base type. */
    val VIEW_CLASS_ID = ClassId.topLevel(FqName(VIEW_CLASS))

    /** The class ID for the Android `ViewGroup` base type. */
    val VIEW_GROUP_CLASS_ID = ClassId.topLevel(FqName(VIEW_GROUP_CLASS))

    /** The class ID for the Android `ViewGroup.LayoutParams` base type. */
    val VIEW_GROUP_LAYOUT_PARAMS_CLASS_ID = VIEW_GROUP_CLASS_ID.createNestedClassId(Name.identifier("LayoutParams"))

    /** The class ID for the Android `Context` type. */
    val CONTEXT_CLASS_ID = ClassId.topLevel(FqName(CONTEXT_CLASS))

    /** The class ID for the Android `AttributeSet` type. */
    val ATTRIBUTE_SET_CLASS_ID = ClassId.topLevel(FqName(ATTRIBUTE_SET_CLASS))
}