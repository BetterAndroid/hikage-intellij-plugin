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
 * This file is created by fankes on 2026/7/31.
 */
package com.highcapable.hikage.convert.planner

import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Argument
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.MemberKind
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Value
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.symbol.AndroidSymbols

/**
 * Plans Android margin and padding groups through the BetterAndroid and AndroidX update extensions.
 */
object SpacingPlanner {

    private const val BETTER_ANDROID_VIEW_EXTENSION_PACKAGE = "com.highcapable.betterandroid.ui.extension.view"
    private const val ANDROIDX_VIEW_EXTENSION_PACKAGE = "androidx.core.view"

    private val SPACING_DIMENSION = "^([-+]?[0-9]+)(dp|dip|px)$".toRegex(RegexOption.IGNORE_CASE)

    private val MARGIN_NAMES = setOf(
        "layout_margin",
        "layout_marginHorizontal",
        "layout_marginVertical",
        "layout_marginLeft",
        "layout_marginTop",
        "layout_marginRight",
        "layout_marginBottom",
        "layout_marginStart",
        "layout_marginEnd"
    )
    private val PADDING_NAMES = setOf(
        "padding",
        "paddingHorizontal",
        "paddingVertical",
        "paddingLeft",
        "paddingTop",
        "paddingRight",
        "paddingBottom",
        "paddingStart",
        "paddingEnd"
    )

    /**
     * Describes one recognized spacing group and whether it converted atomically.
     * @param initializers the ordered extension calls needed to reproduce the group.
     * @param attributes the original attributes in the recognized group.
     * @param isConverted whether every recognized attribute is represented by [initializers].
     */
    data class Result(
        val initializers: List<KotlinLayoutInitializer>,
        val attributes: List<XmlLayoutAttribute>,
        val isConverted: Boolean
    )

    /**
     * Plans margin updates only when the parent contract is a MarginLayoutParams subtype.
     * @param attributes all layout attributes declared by one XML View.
     * @param isMarginLayoutParams whether the proven parent LayoutParams supports margins.
     * @return the recognized margin group and its atomic conversion state.
     */
    fun planMargins(attributes: List<XmlLayoutAttribute>, isMarginLayoutParams: Boolean): Result {
        val margins = attributes.filter { attribute -> attribute.isAndroidSpacing(MARGIN_NAMES) }
        if (margins.isEmpty()) return Result(emptyList(), emptyList(), false)
        if (!isMarginLayoutParams) return Result(emptyList(), margins, false)

        val values = margins.toSpacingValues(allowNegative = true) ?: return Result(emptyList(), margins, false)

        val all = values["layout_margin"]
        val horizontal = values["layout_marginHorizontal"] ?: all
        val vertical = values["layout_marginVertical"] ?: all

        val axisArguments = buildList {
            horizontal?.takeUnless(SpacingValue::isNegative)?.let { value -> add("horizontal" to value.value) }
            vertical?.takeUnless(SpacingValue::isNegative)?.let { value -> add("vertical" to value.value) }
        }
        val absoluteArguments = linkedMapOf<String, Value>().apply {
            horizontal?.takeIf(SpacingValue::isNegative)?.let { value ->
                put("left", value.value)
                put("right", value.value)
            }
            vertical?.takeIf(SpacingValue::isNegative)?.let { value ->
                put("top", value.value)
                put("bottom", value.value)
            }
            values["layout_marginLeft"]?.let { value -> put("left", value.value) }
            values["layout_marginTop"]?.let { value -> put("top", value.value) }
            values["layout_marginRight"]?.let { value -> put("right", value.value) }
            values["layout_marginBottom"]?.let { value -> put("bottom", value.value) }
        }.toList()

        val relativeArguments = buildList {
            values["layout_marginStart"]?.let { value -> add("start" to value.value) }
            values["layout_marginEnd"]?.let { value -> add("end" to value.value) }
        }
        val initializers = buildList {
            addUpdate("updateMargins", BETTER_ANDROID_VIEW_EXTENSION_PACKAGE, axisArguments)
            addUpdate("updateMargins", ANDROIDX_VIEW_EXTENSION_PACKAGE, absoluteArguments)
            addUpdate("updateMarginsRelative", ANDROIDX_VIEW_EXTENSION_PACKAGE, relativeArguments)
        }

        return Result(initializers, margins, true)
    }

    /**
     * Plans padding updates when the complete padding group is representable in View `init`.
     * @param attributes all ordinary View attributes declared by one XML View.
     * @param runtimeDimensionValue resolves a proven runtime dimension for one padding attribute.
     * @return the recognized padding group and its atomic conversion state.
     */
    fun planPadding(
        attributes: List<XmlLayoutAttribute>,
        runtimeDimensionValue: (XmlLayoutAttribute) -> Value? = { null }
    ): Result {
        val paddings = attributes.filter { attribute -> attribute.isAndroidSpacing(PADDING_NAMES) }
        if (paddings.isEmpty()) return Result(emptyList(), emptyList(), false)

        val values = paddings.toSpacingValues(
            allowNegative = false,
            runtimeDimensionValue = runtimeDimensionValue
        ) ?: return Result(emptyList(), paddings, false)

        val hasAbsoluteHorizontal = "paddingLeft" in values || "paddingRight" in values
        val hasRelativeHorizontal = "paddingStart" in values || "paddingEnd" in values
        if (hasAbsoluteHorizontal && hasRelativeHorizontal) return Result(emptyList(), paddings, false)

        val all = values["padding"]
        val horizontal = values["paddingHorizontal"] ?: all
        val vertical = values["paddingVertical"] ?: all

        val axisArguments = buildList {
            horizontal?.let { value -> add("horizontal" to value.value) }
            vertical?.let { value -> add("vertical" to value.value) }
        }
        val sideArguments = if (hasRelativeHorizontal) buildList {
            values["paddingStart"]?.let { value -> add("start" to value.value) }
            values["paddingTop"]?.let { value -> add("top" to value.value) }
            values["paddingEnd"]?.let { value -> add("end" to value.value) }
            values["paddingBottom"]?.let { value -> add("bottom" to value.value) }
        } else buildList {
            values["paddingLeft"]?.let { value -> add("left" to value.value) }
            values["paddingTop"]?.let { value -> add("top" to value.value) }
            values["paddingRight"]?.let { value -> add("right" to value.value) }
            values["paddingBottom"]?.let { value -> add("bottom" to value.value) }
        }

        val axisFunctionName = if (hasRelativeHorizontal) "updatePaddingRelative" else "updatePadding"
        val initializers = buildList {
            addUpdate(axisFunctionName, BETTER_ANDROID_VIEW_EXTENSION_PACKAGE, axisArguments)
            addUpdate(axisFunctionName, ANDROIDX_VIEW_EXTENSION_PACKAGE, sideArguments)
        }

        return Result(initializers, paddings, true)
    }

    private fun MutableList<KotlinLayoutInitializer>.addUpdate(
        functionName: String,
        packageName: String,
        arguments: List<Pair<String, Value>>
    ) {
        if (arguments.isEmpty()) return
        add(KotlinLayoutInitializer(
            memberName = functionName,
            memberKind = MemberKind.METHOD,
            arguments = arguments.map { (name, value) -> Argument(name, value) },
            importName = "$packageName.$functionName"
        ))
    }

    private fun List<XmlLayoutAttribute>.toSpacingValues(
        allowNegative: Boolean,
        runtimeDimensionValue: (XmlLayoutAttribute) -> Value? = { null }
    ): Map<String, SpacingValue>? {
        val values = mapNotNull { attribute ->
            val value = attribute.toSpacingValue(allowNegative)
                ?: runtimeDimensionValue(attribute)?.let { runtimeValue ->
                    SpacingValue(runtimeValue, isNegative = false)
                }
            value?.let { attribute.localName to it }
        }.toMap()
        return values.takeIf { parsed -> parsed.size == size }
    }

    private fun XmlLayoutAttribute.toSpacingValue(allowNegative: Boolean): SpacingValue? {
        val match = SPACING_DIMENSION.matchEntire(value) ?: return null
        val magnitude = match.groupValues[1].toIntOrNull() ?: return null
        if (!allowNegative && magnitude < 0) return null

        val converted = when (match.groupValues[2].lowercase()) {
            "dp", "dip" -> Value.Dp(magnitude)
            "px" -> Value.IntegerLiteral(magnitude.toLong())
            else -> return null
        }
        return SpacingValue(converted, isNegative = magnitude < 0)
    }

    private fun XmlLayoutAttribute.isAndroidSpacing(names: Set<String>) = localName in names &&
        (namespaceUri == AndroidSymbols.NAMESPACE_URI || namespaceUri.isEmpty() && namespacePrefix == "android")

    private data class SpacingValue(
        val value: Value,
        val isNegative: Boolean
    )
}