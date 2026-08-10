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

import com.highcapable.hikage.convert.model.KotlinLayoutCall.TypeReference
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.symbol.HikageSymbols
import com.squareup.kotlinpoet.CodeBlock

/**
 * Wraps one normalized layout tree as a directly reusable Hikagable property.
 */
object HikagablePropertyRenderer {

    /**
     * Renders [root] as a property named from [layoutResourceName].
     * @param root the wrapper-independent call tree.
     * @param layoutResourceName the Android XML resource basename without its extension.
     * @param explicitRootLayoutParams a stronger parent LayoutParams contract, or null for Hikage's default contract.
     * @return the plain Kotlin property snippet and its deterministic symbol imports.
     */
    fun render(
        root: KotlinLayoutNode,
        layoutResourceName: String,
        explicitRootLayoutParams: TypeReference?
    ): KotlinSnippet {
        val layout = KotlinLayoutRenderer.render(root)
        val declarationName = layoutResourceName.toDeclarationName()
        require(declarationName.isNotEmpty()) { "The XML layout resource name must produce a Kotlin declaration name." }
        val header = if (explicitRootLayoutParams == null) CodeBlock.of(
            "val %N = %N {\n",
            declarationName,
            HikageSymbols.HIKAGABLE_FUNCTION_NAME
        ) else CodeBlock.of(
            "val %N = %N<%L> {\n",
            declarationName,
            HikageSymbols.HIKAGABLE_FUNCTION_NAME,
            explicitRootLayoutParams.name
        )

        return KotlinSnippet(
            code = header.toString() + layout.code.prependIndent("    ") + "\n}",
            imports = (layout.imports + HikageSymbols.HIKAGABLE_FUNCTION +
                listOfNotNull(explicitRootLayoutParams?.importName)).distinct().sorted(),
            unqualifiedResourceClassName = layout.unqualifiedResourceClassName
        )
    }

    private fun String.toDeclarationName() = split('_')
        .filter(String::isNotEmpty)
        .joinToString("") { segment -> segment.replaceFirstChar { character -> character.uppercaseChar() } }
}