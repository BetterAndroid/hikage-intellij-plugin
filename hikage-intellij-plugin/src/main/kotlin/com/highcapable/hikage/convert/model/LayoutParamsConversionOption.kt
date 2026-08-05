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
 * This file is created by fankes on 2026/7/26.
 */
package com.highcapable.hikage.convert.model

/**
 * Selects how XML layout attributes are represented during conversion.
 */
enum class LayoutParamsConversionOption {

    /** Keeps every `layout_*` value in Hikage `attrs`. */
    FULLY_ATTRIBUTES,

    /** Prefers an atomic explicit LayoutParams conversion and otherwise falls back to `attrs`. */
    COMPATIBLE_MODE,

    /** Emits explicit LayoutParams values and preserves unsupported values as TODO items. */
    LAYOUT_PARAMS_ONLY;

    /**
     * Returns the effective option for the target module's runtime-attribute capability.
     * @param isRuntimeAttributeEnabled whether `hikage-runtime-attribute` is available.
     */
    fun effectiveOption(isRuntimeAttributeEnabled: Boolean) = if (isRuntimeAttributeEnabled) this else LAYOUT_PARAMS_ONLY
}