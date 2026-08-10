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
package com.highcapable.hikage.convert

import com.highcapable.hikage.convert.generator.HikagablePropertyRenderer
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinLayoutCall.TypeReference
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.convert.parser.XmlLayoutParser
import com.highcapable.hikage.convert.planner.LayoutParamsPlanner
import com.highcapable.hikage.convert.planner.SpacingPlanner
import com.highcapable.hikage.symbol.AndroidSymbols
import com.intellij.psi.xml.XmlFile

/**
 * Converts one Android XML layout into a directly reusable Hikagable property snippet.
 */
object HikagablePropertyConverter {

    /**
     * Converts [file] with the strongest safe Hikagable parent LayoutParams contract required by its root.
     * @param file the revalidated Android XML layout file.
     * @return generated source and structured conversion diagnostics.
     */
    fun convert(file: XmlFile): ConversionOutcome<KotlinSnippet> {
        val rootLayoutParams = file.rootLayoutParamsType()
        val resolved = XmlLayoutConverter.convert(
            file = file,
            rootLayoutParamsClass = rootLayoutParams.className
        )
        return ConversionOutcome(
            value = resolved.value?.let { root ->
                HikagablePropertyRenderer.render(
                    root = root,
                    layoutResourceName = file.name.substringBeforeLast('.'),
                    explicitRootLayoutParams = rootLayoutParams.explicitType
                )
            },
            diagnostics = resolved.diagnostics
        )
    }

    private fun XmlFile.rootLayoutParamsType(): RootLayoutParams {
        val layoutAttributes = XmlLayoutParser.parse(this).value?.root?.attributes
            ?.filter { attribute -> attribute.kind == XmlLayoutAttribute.Kind.LAYOUT }
            .orEmpty()
        val marginPlan = SpacingPlanner.planMargins(
            attributes = layoutAttributes,
            isMarginLayoutParams = true
        )
        val layoutParamsPlan = LayoutParamsPlanner.plan(
            attributes = layoutAttributes,
            option = LayoutParamsConversionOption.COMPATIBLE_MODE,
            parentLayoutParamsClass = AndroidSymbols.VIEW_GROUP_MARGIN_LAYOUT_PARAMS_CLASS,
            isMarginLayoutParams = true
        )
        val hasConvertibleCanonicalMarginGroup = marginPlan.attributes.isNotEmpty() &&
            marginPlan.isConverted && layoutParamsPlan.layoutParams != null && layoutParamsPlan.attributes.isEmpty()
        if (!hasConvertibleCanonicalMarginGroup) return RootLayoutParams(
            className = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            explicitType = null
        )
        return RootLayoutParams(
            className = AndroidSymbols.VIEW_GROUP_MARGIN_LAYOUT_PARAMS_CLASS,
            explicitType = TypeReference(
                name = "${AndroidSymbols.VIEW_GROUP_NAME}.MarginLayoutParams",
                importName = AndroidSymbols.VIEW_GROUP_CLASS
            )
        )
    }

    private data class RootLayoutParams(
        val className: String,
        val explicitType: TypeReference?
    )
}