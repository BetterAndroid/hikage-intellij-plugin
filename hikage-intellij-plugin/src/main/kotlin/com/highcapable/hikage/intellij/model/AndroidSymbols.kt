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

/**
 * Fully qualified Android symbols used by the IDE plugin.
 */
object AndroidSymbols {

    /** The Android View base type. */
    const val VIEW = "android.view.View"

    /** The Android ViewGroup base type. */
    const val VIEW_GROUP = "android.view.ViewGroup"

    /** The Android ViewGroup LayoutParams type. */
    const val VIEW_GROUP_LAYOUT_PARAMS = "$VIEW_GROUP.LayoutParams"

    /** The Android Context type. */
    const val CONTEXT = "android.content.Context"

    /** The Android AttributeSet type. */
    const val ATTRIBUTE_SET = "android.util.AttributeSet"

    /** The class ID for the Android View base type. */
    val VIEW_CLASS_ID = ClassId.topLevel(FqName(VIEW))

    /** The class ID for the Android ViewGroup base type. */
    val VIEW_GROUP_CLASS_ID = ClassId.topLevel(FqName(VIEW_GROUP))

    /** The class ID for the Android Context type. */
    val CONTEXT_CLASS_ID = ClassId.topLevel(FqName(CONTEXT))

    /** The class ID for the Android AttributeSet type. */
    val ATTRIBUTE_SET_CLASS_ID = ClassId.topLevel(FqName(ATTRIBUTE_SET))
}