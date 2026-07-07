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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

private const val HIKAGE_CALL_TEXT_ATTRIBUTES_NAME = "HikageCallTextAttributes"

private val HIKAGE_CALL_TEXT_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
    HIKAGE_CALL_TEXT_ATTRIBUTES_NAME,
    DefaultLanguageHighlighterColors.FUNCTION_CALL
)

/** Text attributes used for Hikage DSL calls. */
val HIKAGE_CALL_TEXT_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(
    HighlightInfoType.SYMBOL_TYPE_SEVERITY,
    HIKAGE_CALL_TEXT_ATTRIBUTES_KEY
)