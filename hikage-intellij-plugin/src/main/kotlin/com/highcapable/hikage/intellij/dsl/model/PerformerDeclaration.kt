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
package com.highcapable.hikage.intellij.dsl.model

import com.highcapable.hikage.intellij.model.HikageSymbols
import com.intellij.openapi.vfs.VirtualFile

/**
 * Represents a normalized Hikage performer declaration.
 */
data class PerformerDeclaration(
    val spec: PerformerSpec,
    val declaration: ViewDeclaration,
    val source: Source,
    /** The physical file that provides this declaration. */
    val originFile: VirtualFile?
) {

    /**
     * The source kind used by KSP conflict and optional-declaration rules.
     */
    enum class Source {
        ANNOTATION,
        STRICT_FILE,
        OPTIONAL_FILE
    }

    /** The fully qualified Android view class name. */
    val viewClass get() = declaration.viewClass

    /** The generated performer function name. */
    val functionName get() = declaration.functionName

    /** The package containing the generated performer function. */
    val generatedPackageName get() = "${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.${declaration.packageName}"

    /** The key used to identify the generated performer function. */
    val generatedKey get() = "$generatedPackageName.$functionName"
}