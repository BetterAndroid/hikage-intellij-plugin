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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.utils.extension

import com.highcapable.hikage.symbol.SystemSymbols
import org.jetbrains.kotlin.psi.KtFile

/**
 * Resolves a class name from this file's package and imports without Kotlin analysis.
 * @param typeText the class name to resolve.
 * @return [String] or null.
 */
fun KtFile.resolveClassName(typeText: String): String? {
    if (typeText.isBlank()) return null
    if (typeText == SystemSymbols.KOTLIN_ANY.substringAfterLast(".")) return SystemSymbols.KOTLIN_ANY
    if (typeText.contains(".") && typeText.substringBefore(".").firstOrNull()?.isLowerCase() == true) return typeText

    importDirectives.forEach { directive ->
        val importedFqName = directive.importedFqName?.asString() ?: return@forEach
        val alias = directive.aliasName
        val importName = alias ?: importedFqName.substringAfterLast(".")
        if (typeText == importName || typeText.startsWith("$importName.")) {
            return importedFqName + typeText.removePrefix(importName)
        }
    }
    importDirectives.forEach { directive ->
        val importedFqName = directive.importedFqName?.asString() ?: return@forEach
        if (directive.isAllUnder && !typeText.contains(".")) return "$importedFqName.$typeText"
    }

    val packageName = packageFqName.asString()
    return if (packageName.isBlank()) typeText else "$packageName.$typeText"
}