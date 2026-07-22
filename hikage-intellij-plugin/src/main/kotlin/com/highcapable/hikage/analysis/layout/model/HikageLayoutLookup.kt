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
 * This file is created by fankes on 2026/7/23.
 */
package com.highcapable.hikage.analysis.layout.model

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * A statically resolved lookup into a Hikage layout.
 */
sealed interface HikageLayoutLookup {

    /** The complete lookup expression. */
    val expression: KtExpression

    /** The Hikage value owning the resolved layout. */
    val receiver: KtExpression

    /**
     * A layout-ID lookup whose static ID value and declaration are resolved.
     * @param expression the complete array-access or qualified call expression.
     * @param receiver the Hikage value owning the resolved layout.
     * @param idExpression the source expression supplying the runtime string ID.
     * @param layoutId the matching ID declaration from the resolved layout model.
     */
    data class Id(
        override val expression: KtExpression,
        override val receiver: KtExpression,
        val idExpression: KtExpression,
        val layoutId: HikageLayout.Id
    ) : HikageLayoutLookup

    /**
     * A root lookup whose receiver and layout root are statically resolved.
     * @param expression the complete qualified root lookup expression.
     * @param receiver the Hikage value owning the resolved layout.
     * @param call the root lookup call selector.
     * @param typeReference the explicitly requested root View type, or null when inferred.
     * @param layoutRoot the resolved root declaration from the layout model.
     */
    data class Root(
        override val expression: KtExpression,
        override val receiver: KtExpression,
        val call: KtCallExpression,
        val typeReference: KtTypeReference?,
        val layoutRoot: HikageLayout.Root
    ) : HikageLayoutLookup
}