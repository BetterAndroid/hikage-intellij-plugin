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
 * This file is created by fankes on 2026/8/10.
 */
package com.highcapable.hikage.convert.generator

import com.highcapable.hikage.convert.model.KotlinLayoutNode

/**
 * Wraps the shared layout renderer as a Performer-body snippet output.
 */
object PerformerSnippetRenderer {

    /**
     * Renders [root] for insertion into an existing Hikage Performer body.
     * @param root the wrapper-independent call tree.
     * @return the plain Kotlin source snippet and its deterministic symbol imports.
     */
    fun render(root: KotlinLayoutNode) = KotlinLayoutRenderer.render(root)
}