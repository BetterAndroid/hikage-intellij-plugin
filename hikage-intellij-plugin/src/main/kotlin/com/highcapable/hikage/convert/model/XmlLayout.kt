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
package com.highcapable.hikage.convert.model

/**
 * Represents a neutral XML layout document independent of output mode.
 * @param root the effective layout root.
 * @param sourceFileUrl the original XML source file URL.
 * @param hasDataBindingWrapper whether a root Data Binding wrapper was unwrapped.
 * @param dataBindingDeclarationCount the retained number of Data Binding declarations.
 */
data class XmlLayout(
    val root: XmlLayoutNode,
    val sourceFileUrl: String,
    val hasDataBindingWrapper: Boolean = false,
    val dataBindingDeclarationCount: Int = 0
)