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
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.dsl.model

import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.ClassNameValidator

/**
 * Represents the resolved view identity used by Hikage KSP generation.
 */
data class ViewDeclaration(
    val packageName: String,
    val className: String,
    val alias: String?,
    val isViewGroup: Boolean
) {

    companion object {

        /**
         * Creates a View declaration using the same class and alias rules as Hikage KSP.
         * @param viewClass the fully qualified View class name.
         * @param alias the optional generated performer alias.
         * @param isViewGroup whether the View is a ViewGroup.
         * @return [ViewDeclaration] or null when the declaration cannot generate a performer.
         */
        fun from(viewClass: String, alias: String?, isViewGroup: Boolean): ViewDeclaration? {
            if (viewClass == AndroidSymbols.VIEW_GROUP_CLASS) return null

            val packageName = viewClass.packageName() ?: return null
            val className = viewClass.removePrefix("$packageName.")
            val resolvedAlias = alias?.takeIf(String::isNotBlank)
                ?: className.takeIf { name -> name.contains(".") }?.replace(".", "_")
            if (resolvedAlias != null && !ClassNameValidator.check(resolvedAlias)) return null

            return ViewDeclaration(packageName, className, resolvedAlias, isViewGroup)
        }

        private fun String.packageName(): String? {
            val parts = split(".")
            val classStartIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
                .takeIf { index -> index > 0 }
                ?: return null

            return parts.take(classStartIndex).joinToString(".")
        }
    }

    /** The fully qualified Android view class name. */
    val viewClass get() = "$packageName.$className"

    /** The generated performer function name. */
    val functionName get() = alias ?: className

    /** The package containing the generated performer function. */
    val generatedPackageName get() = "${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.$packageName"

    /** The key used to identify the generated performer function. */
    val generatedKey get() = "$generatedPackageName.$functionName"
}