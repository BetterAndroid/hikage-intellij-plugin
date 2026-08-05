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
package com.highcapable.hikage.convert.bundle

import com.highcapable.kavaref.extension.classOf
import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Provides localized messages for XML layout conversion.
 */
object ConversionBundle {

    @NonNls
    private const val BUNDLE = "messages.ConversionBundle"

    private val bundle = DynamicBundle(classOf<ConversionBundle>(), BUNDLE)

    /**
     * Returns a localized message for [key].
     * @param key the message bundle key.
     * @param args the message format arguments.
     * @return localized text.
     */
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg args: Any) = bundle.getMessage(key, *args)
}