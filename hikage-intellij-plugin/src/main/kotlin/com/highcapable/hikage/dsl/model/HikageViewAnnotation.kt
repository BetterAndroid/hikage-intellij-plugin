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
 */
package com.highcapable.hikage.dsl.model

import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.attributeArgument
import org.jetbrains.kotlin.psi.KtAnnotationEntry

/**
 * Defines the supported Hikage View annotations and their positional argument layouts.
 */
sealed class HikageViewAnnotation(
    val fqName: String,
    private val positionalArguments: Map<Parameter, Int>
) {

    companion object {

        val entries = listOf(View, Declaration)
    }

    object View : HikageViewAnnotation(
        HikageSymbols.HIKAGE_VIEW_ANNOTATION,
        mapOf(
            Parameter.LPARAMS to 0,
            Parameter.ALIAS to 1,
            Parameter.ATTRS to 2,
            Parameter.INIT to 3,
            Parameter.PERFORMER to 4
        )
    )

    object Declaration : HikageViewAnnotation(
        HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION,
        mapOf(
            Parameter.VIEW to 0,
            Parameter.LPARAMS to 1,
            Parameter.ALIAS to 2,
            Parameter.ATTRS to 3,
            Parameter.INIT to 4,
            Parameter.PERFORMER to 5
        )
    )

    val view get() = positionalArguments[Parameter.VIEW]?.let { index ->
        Argument(Parameter.VIEW, index)
    }
    val lparams get() = Argument(
        Parameter.LPARAMS,
        requireNotNull(positionalArguments[Parameter.LPARAMS]) { "Missing lparams argument." }
    )
    val alias get() = Argument(
        Parameter.ALIAS,
        requireNotNull(positionalArguments[Parameter.ALIAS]) { "Missing alias argument." }
    )
    val attrs get() = Argument(
        Parameter.ATTRS,
        requireNotNull(positionalArguments[Parameter.ATTRS]) { "Missing attrs argument." }
    )
    val init get() = Argument(
        Parameter.INIT,
        requireNotNull(positionalArguments[Parameter.INIT]) { "Missing init argument." }
    )
    val performer get() = Argument(
        Parameter.PERFORMER,
        requireNotNull(positionalArguments[Parameter.PERFORMER]) { "Missing performer argument." }
    )

    enum class Parameter(val argumentName: String) {
        VIEW("view"),
        LPARAMS("lparams"),
        ALIAS("alias"),
        ATTRS("attrs"),
        INIT("init"),
        PERFORMER("performer")
    }

    class Argument(
        val parameter: Parameter,
        val positionalIndex: Int
    ) {

        fun value(annotation: KtAnnotationEntry) = annotation.attributeArgument(parameter.argumentName, positionalIndex)
        fun expression(annotation: KtAnnotationEntry) = value(annotation)?.getArgumentExpression()
    }
}