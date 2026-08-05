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
 * This file is created by fankes on 2026/7/30.
 */
package com.highcapable.hikage.convert.model

/**
 * Identifies one callable used to render a wrapper-independent Hikage layout node.
 * @param functionName the imported callable name used in generated code.
 * @param importName the complete callable import.
 * @param typeArguments the explicit generic View and child LayoutParams types.
 * @param hasChildPerformerParameter whether the callable declares a child `performer` after `init`.
 */
data class KotlinLayoutCall(
    val functionName: String,
    val importName: String,
    val typeArguments: List<TypeReference> = emptyList(),
    val hasChildPerformerParameter: Boolean
) {

    /**
     * Identifies one imported Kotlin class reference used as a call type argument.
     * @param name the source-level class name, including any containing classes.
     * @param importName the complete import of its outermost class.
     */
    data class TypeReference(
        val name: String,
        val importName: String
    )
}