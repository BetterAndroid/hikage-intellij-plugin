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
 * Represents one wrapper-independent Hikage performer call.
 * @param viewClassName the resolved Android View class.
 * @param call the generated Performer or proven generic View/ViewGroup call.
 * @param layoutParams the safely planned explicit LayoutParams, or null when attrs own the complete group.
 * @param id the separated Hikage string ID.
 * @param attributes the attributes safe to emit through `attrs`.
 * @param initializers the PSI-proven writes emitted through `init`.
 * @param todoAttributes the attributes retained as adjacent generated TODOs.
 * @param todoComments additional node-level TODO comments.
 * @param children the ordered nested performer calls.
 */
data class KotlinLayoutNode(
    val viewClassName: String,
    val call: KotlinLayoutCall,
    val layoutParams: KotlinLayoutParams?,
    val id: String?,
    val attributes: List<KotlinLayoutAttribute>,
    val initializers: List<KotlinLayoutInitializer>,
    val todoAttributes: List<KotlinLayoutAttribute>,
    val todoComments: List<String>,
    val children: List<KotlinLayoutNode>
)