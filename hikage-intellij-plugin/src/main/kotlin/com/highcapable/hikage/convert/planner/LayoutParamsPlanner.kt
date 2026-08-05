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
package com.highcapable.hikage.convert.planner

import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutParams
import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.symbol.AndroidSymbols

/**
 * Plans one atomic explicit LayoutParams conversion without guessing parent-specific members.
 */
object LayoutParamsPlanner {

    private val INTEGER_DIMENSION = "^([0-9]+)(dp|dip|px)$".toRegex()

    /**
     * Describes the mutually exclusive explicit, attrs-fallback, and TODO outputs of one node.
     * @param layoutParams the explicit common size, or null when it cannot be emitted.
     * @param attributes the complete layout-attribute group that must remain in `attrs`.
     * @param todoAttributes layout attributes that cannot be represented in the selected only option.
     */
    data class Result(
        val layoutParams: KotlinLayoutParams?,
        val attributes: List<XmlLayoutAttribute>,
        val todoAttributes: List<XmlLayoutAttribute>
    )

    /**
     * Plans [attributes] according to [option] and the parent LayoutParams contract supplied by the output context.
     * @param attributes all `layout_*` attributes declared by one XML View.
     * @param option the selected LayoutParams conversion option.
     * @param parentLayoutParamsClass the proven parent LayoutParams class, or null when the contract is unavailable.
     * @param isMarginLayoutParams whether that parent contract supports margin updates.
     * @param memberInitializers parent-specific writable members proven against the concrete LayoutParams class.
     * @return an atomic plan that never mixes explicit LayoutParams with layout attrs.
     */
    fun plan(
        attributes: List<XmlLayoutAttribute>,
        option: LayoutParamsConversionOption,
        parentLayoutParamsClass: String?,
        isMarginLayoutParams: Boolean,
        memberInitializers: Map<XmlLayoutAttribute, KotlinLayoutInitializer> = emptyMap()
    ): Result {
        if (attributes.isEmpty()) return Result(null, emptyList(), emptyList())
        if (option == LayoutParamsConversionOption.FULLY_ATTRIBUTES) return Result(null, attributes, emptyList())

        val widthAttribute = attributes.firstOrNull { attribute ->
            attribute.isAndroidLayoutSize("layout_width")
        }
        val heightAttribute = attributes.firstOrNull { attribute ->
            attribute.isAndroidLayoutSize("layout_height")
        }
        val width = widthAttribute?.toLayoutSize()
        val height = heightAttribute?.toLayoutSize()
        val marginPlan = SpacingPlanner.planMargins(attributes, isMarginLayoutParams)
        val canonicalAttributes = buildSet {
            addAll(setOfNotNull(widthAttribute, heightAttribute))
            addAll(marginPlan.attributes)
        }
        val parentMemberInitializers = memberInitializers.filterKeys { attribute ->
            attribute !in canonicalAttributes
        }
        val layoutParams = if (parentLayoutParamsClass != null && width != null && height != null)
            KotlinLayoutParams(
                width,
                height,
                marginPlan.initializers + attributes.mapNotNull(parentMemberInitializers::get)
            )
        else null
        val convertedAttributes = if (layoutParams == null) emptySet()
        else buildSet {
            addAll(setOfNotNull(widthAttribute, heightAttribute))
            if (marginPlan.isConverted) addAll(marginPlan.attributes)
            addAll(parentMemberInitializers.keys)
        }

        if (option == LayoutParamsConversionOption.COMPATIBLE_MODE) return if (convertedAttributes.size == attributes.size)
            Result(requireNotNull(layoutParams), emptyList(), emptyList())
        else Result(null, attributes, emptyList())

        return if (layoutParams == null) Result(null, emptyList(), attributes)
        else Result(
            layoutParams = layoutParams,
            attributes = emptyList(),
            todoAttributes = attributes.filterNot(convertedAttributes::contains)
        )
    }

    private fun XmlLayoutAttribute.isAndroidLayoutSize(name: String) = localName == name &&
        (namespaceUri == AndroidSymbols.NAMESPACE_URI || namespaceUri.isEmpty() && namespacePrefix == "android")

    private fun XmlLayoutAttribute.toLayoutSize() = when (value) {
        "match_parent", "fill_parent" -> KotlinLayoutParams.Size.MatchParent
        "wrap_content" -> KotlinLayoutParams.Size.WrapContent
        else -> INTEGER_DIMENSION.matchEntire(value)?.let { result ->
            val magnitude = result.groupValues[1].toIntOrNull() ?: return@let null
            when (result.groupValues[2]) {
                "dp", "dip" -> KotlinLayoutParams.Size.Dp(magnitude)
                "px" -> KotlinLayoutParams.Size.Px(magnitude)
                else -> null
            }
        }
    }
}