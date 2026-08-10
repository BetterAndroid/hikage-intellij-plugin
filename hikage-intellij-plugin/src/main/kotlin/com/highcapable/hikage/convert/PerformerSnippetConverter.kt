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
package com.highcapable.hikage.convert

import com.highcapable.hikage.convert.generator.PerformerSnippetRenderer
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.symbol.AndroidSymbols
import com.intellij.psi.xml.XmlFile

/**
 * Executes the read-only XML layout conversion pipeline for Performer snippets.
 */
object PerformerSnippetConverter {

    /**
     * Converts [file] using the current Android, performer, attribute, and settings snapshots.
     * @param file the revalidated Android XML layout file.
     * @return generated source and structured conversion diagnostics.
     */
    fun convert(file: XmlFile): ConversionOutcome<KotlinSnippet> {
        val resolved = XmlLayoutConverter.convert(
            file = file,
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        return ConversionOutcome(
            value = resolved.value?.let(PerformerSnippetRenderer::render),
            diagnostics = resolved.diagnostics
        )
    }
}