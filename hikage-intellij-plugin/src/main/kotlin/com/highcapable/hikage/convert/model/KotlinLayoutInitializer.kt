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
package com.highcapable.hikage.convert.model

/**
 * Represents one generated write in a View `init` or LayoutParams body.
 * @param memberName the resolved writable member name.
 * @param memberKind whether the write uses Kotlin property syntax or a method call.
 * @param arguments the statically proven call arguments in declaration order.
 * @param importName the exact extension import required by this write, or null for receiver members.
 */
data class KotlinLayoutInitializer(
    val memberName: String,
    val memberKind: MemberKind,
    val arguments: List<Argument>,
    val importName: String? = null
) {

    /**
     * Represents one positional or named initializer argument.
     * @param name the Kotlin parameter name, or null for a positional value.
     * @param value the statically proven value.
     */
    data class Argument(
        val name: String? = null,
        val value: Value
    )

    /** The supported writable-member shapes. */
    enum class MemberKind {

        /** A Kotlin property assignment. */
        PROPERTY,

        /** A method call. */
        METHOD
    }

    /** A statically proven Kotlin value used by an initializer. */
    sealed interface Value {

        /** A Kotlin string literal. */
        data class Text(val value: String) : Value

        /** A Kotlin boolean literal. */
        data class BooleanLiteral(val value: Boolean) : Value

        /** A Kotlin integer literal. */
        data class IntegerLiteral(val value: Long) : Value

        /**
         * A public constant proven from the target member's Android metadata.
         * @param importName the exact declaring top-level class import.
         * @param qualifier the class qualifier rendered before the constant name.
         * @param memberName the public static constant name.
         */
        data class SymbolicConstant(
            val importName: String,
            val qualifier: String,
            val memberName: String
        ) : Value

        /** A bitwise combination of proven symbolic integer constants. */
        data class BitwiseOr(val values: List<SymbolicConstant>) : Value

        /** A Kotlin floating-point literal. */
        data class FloatingPointLiteral(val value: String, val isFloat: Boolean) : Value

        /** An integer `dp` value resolved through the enclosing Performer density scope. */
        data class Dp(val value: Int) : Value

        /**
         * A proven Android resource reference with an optional Hikage resource helper.
         * @param resourceClassName the exact module or framework `R` class identity.
         * @param resourceType the Android resource type segment.
         * @param resourceName the unqualified Android resource name.
         * @param helperName the Hikage resource helper, or null when the target accepts the resource ID itself.
         */
        data class Resource(
            val resourceClassName: String,
            val resourceType: String,
            val resourceName: String,
            val helperName: String?
        ) : Value

        /**
         * A theme attribute resolved through a BetterAndroid `Context` extension in View `init`.
         * @param resourceClassName the exact module or framework `R` class identity.
         * @param resourceName the unqualified theme attribute resource name.
         * @param functionName the unqualified BetterAndroid extension function name.
         * @param importName the exact BetterAndroid extension import.
         * @param isCurrentModuleResource whether the resource belongs to the module being converted.
         */
        data class ThemeAttribute(
            val resourceClassName: String,
            val resourceName: String,
            val functionName: String,
            val importName: String,
            val isCurrentModuleResource: Boolean
        ) : Value

        /**
         * A proven extension call applied to another converted value.
         * @param receiver the converted receiver value.
         * @param importName the exact extension import, or null for Kotlin standard-library members.
         * @param functionName the unqualified extension function name.
         */
        data class ExtensionCall(
            val receiver: Value,
            val importName: String?,
            val functionName: String
        ) : Value

        /** A public property read from the current View or LayoutParams receiver. */
        data class ReceiverProperty(val memberName: String) : Value

        /** A Kotlin null literal. */
        data object Null : Value
    }
}