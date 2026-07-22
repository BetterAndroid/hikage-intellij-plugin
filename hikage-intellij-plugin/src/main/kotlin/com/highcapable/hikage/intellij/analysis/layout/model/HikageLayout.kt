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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.intellij.analysis.layout.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement

/**
 * The reusable result of resolving one Hikage receiver back to its layout source.
 * @param ids the stable runtime IDs available from the receiver.
 * @param root the unique statically resolved root View, or null when it is ambiguous.
 */
data class HikageLayout(
    val ids: List<Id>,
    val root: Root?
) {

    /**
     * A statically resolved Hikage layout ID and the View type stored under it.
     * @param name the runtime string ID.
     * @param viewClass the unique compatible View type, or null when source branches disagree.
     * @param declaration the source expression declaring the ID.
     * @param isAlwaysPresent whether every statically possible layout source declares this ID.
     */
    data class Id(
        val name: String,
        val viewClass: PsiClass?,
        val declaration: PsiElement,
        val isAlwaysPresent: Boolean = true
    )

    /**
     * The statically resolved root View of a layout.
     * @param viewClass the root View type.
     * @param declaration the first performer call providing the root.
     */
    data class Root(
        val viewClass: PsiClass,
        val declaration: PsiElement
    )
}