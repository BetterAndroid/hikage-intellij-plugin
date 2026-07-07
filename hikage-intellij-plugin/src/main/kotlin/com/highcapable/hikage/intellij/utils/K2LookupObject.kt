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
 * This file is created by fankes on 2026/7/8.
 */
package com.highcapable.hikage.intellij.utils

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.lazyClassOrNull

/**
 * Provides utilities for working with K2 lookup objects in the Kotlin plugin.
 */
object K2LookupObject {

    private const val K2_FUNCTION_CALL_LOOKUP_OBJECT = "org.jetbrains.kotlin.idea.completion.impl.k2.lookups.factories.FunctionCallLookupObject"
    private const val K2_CLASSIFIER_LOOKUP_OBJECT = "org.jetbrains.kotlin.idea.completion.impl.k2.lookups.factories.ClassifierLookupObject"

    private val FunctionCallLookupObjectClass by lazyClassOrNull(K2_FUNCTION_CALL_LOOKUP_OBJECT)

    private val getHasReceiver by lazy {
        FunctionCallLookupObjectClass
            ?.resolve()
            ?.optional()
            ?.firstMethodOrNull { name = "getHasReceiver" }
    }

    fun isClassifier(`object`: Any) = `object`.javaClass.name == K2_CLASSIFIER_LOOKUP_OBJECT

    fun isReceiverFunction(`object`: Any): Boolean {
        if (`object`.javaClass.name != K2_FUNCTION_CALL_LOOKUP_OBJECT) return false

        // K2 lookup objects are internal Kotlin plugin classes, so use a narrow runtime check
        // instead of depending on non-public completion APIs that can break binary loading.
        return getHasReceiver?.copy()?.of(`object`)?.invokeQuietly<Boolean>() == true
    }
}