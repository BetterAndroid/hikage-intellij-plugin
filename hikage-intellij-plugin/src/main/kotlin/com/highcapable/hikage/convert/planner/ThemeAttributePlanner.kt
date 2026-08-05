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
 * This file is created by fankes on 2026/8/2.
 */
package com.highcapable.hikage.convert.planner

import com.android.ide.common.rendering.api.AttributeFormat
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Value
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver

/**
 * Creates runtime theme-attribute values backed by BetterAndroid's `Context` extensions.
 */
object ThemeAttributePlanner {

    private const val BETTER_ANDROID_RESOURCE_EXTENSION_PACKAGE = "com.highcapable.betterandroid.ui.extension.component.base"

    /**
     * The supported runtime result contracts.
     */
    enum class ResultType(val functionName: String) {

        /** A Boolean theme value. */
        BOOLEAN("getThemeAttrsBoolean"),

        /** A Float theme value. */
        FLOAT("getThemeAttrsFloat"),

        /** An integer theme value. */
        INTEGER("getThemeAttrsInteger"),

        /** A String theme value. */
        STRING("getThemeAttrsString"),

        /** A dimension theme value in pixels as Float. */
        DIMENSION("getThemeAttrsDimension"),

        /** A Drawable theme value. */
        DRAWABLE("getThemeAttrsDrawable"),

        /** A ColorStateList theme value. */
        COLOR_STATE_LIST("getThemeAttrsColorStateList"),

        /** A color-int theme value. */
        COLOR("getThemeAttrsColor"),

        /** The resource ID referenced by a theme value. */
        RESOURCE_ID("getThemeAttrsId")
    }

    /**
     * Plans a resolved theme attribute using its declaration owner.
     * @param rawValue the original XML theme reference.
     * @param definition the resolved Android theme-attribute definition.
     * @param currentModuleResourcePackageName the converted module's authoritative `R` package.
     * @param resultType the proven runtime value contract.
     * @return a generated theme value, or null when the reference identity is unavailable.
     */
    fun plan(
        rawValue: String,
        definition: AndroidAttributeResolver.Attribute,
        currentModuleResourcePackageName: String?,
        resultType: ResultType
    ) = plan(
        rawValue = rawValue,
        definitionPackageName = definition.declarationPackageName,
        isCurrentModuleResource = definition.declarationPackageName == currentModuleResourcePackageName,
        resultType = resultType
    )

    /**
     * Plans a theme dimension for an integer pixel contract.
     * @param rawValue the original XML theme reference.
     * @param definition the resolved Android theme-attribute definition.
     * @param currentModuleResourcePackageName the converted module's authoritative `R` package.
     * @return a generated dimension value converted to Int, or null when its contract is not proven.
     */
    fun planIntegerDimension(
        rawValue: String,
        definition: AndroidAttributeResolver.Attribute,
        currentModuleResourcePackageName: String?
    ): Value.ExtensionCall? {
        if (AttributeFormat.DIMENSION !in definition.formats) return null

        val themeValue = plan(
            rawValue = rawValue,
            definition = definition,
            currentModuleResourcePackageName = currentModuleResourcePackageName,
            resultType = ResultType.DIMENSION
        ) ?: return null
        return Value.ExtensionCall(
            receiver = themeValue,
            importName = null,
            functionName = "toInt"
        )
    }

    /**
     * Plans a valid theme reference using [resultType].
     * @param rawValue the original XML theme reference.
     * @param definitionPackageName the package that owns the resolved theme-attribute declaration.
     * @param isCurrentModuleResource whether the declaration belongs to the module being converted.
     * @param resultType the proven runtime value contract.
     * @return a generated theme value, or null when the reference identity is unavailable.
     */
    fun plan(
        rawValue: String,
        definitionPackageName: String?,
        isCurrentModuleResource: Boolean,
        resultType: ResultType
    ): Value.ThemeAttribute? {
        val resourceUrl = ResourceUrl.parse(rawValue) ?: return null
        if (!resourceUrl.isTheme || resourceUrl.type != ResourceType.ATTR || !resourceUrl.hasValidName()) return null
        val packageName = definitionPackageName?.takeIf(String::isNotBlank) ?: return null

        return Value.ThemeAttribute(
            resourceClassName = "$packageName.R",
            resourceName = resourceUrl.name,
            functionName = resultType.functionName,
            importName = "$BETTER_ANDROID_RESOURCE_EXTENSION_PACKAGE.${resultType.functionName}",
            isCurrentModuleResource = isCurrentModuleResource
        )
    }
}