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
 * This file is created by fankes on 2026/7/21.
 */
package com.highcapable.hikage.intellij.mirror.lint.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Stores the statically reconstructed Android layout trees for one Kotlin file.
 * @param roots the independent root component nodes found in the file.
 */
data class LayoutSnapshot(
    val roots: List<Node>
) {

    /**
     * Describes one component call projected to an XML-compatible element.
     * @param call the source component call.
     * @param element the PSI element used for missing-attribute diagnostics.
     * @param viewClass the resolved Android View class returned by the call.
     * @param tagName the XML tag selected for the active detector set.
     * @param attributes the statically known Android attributes from the runtime attrs block.
     * @param isAttributeModelComplete whether absence-based diagnostics can safely trust the snapshot.
     * @param nonAttrsAttributeNames attributes explicitly assigned through init or a retained View reference.
     * @param children the component calls nested in the parent performer scope.
     */
    data class Node(
        val call: KtCallExpression,
        val element: PsiElement,
        val viewClass: PsiClass,
        val tagName: String,
        val attributes: List<Attribute>,
        val isAttributeModelComplete: Boolean,
        val nonAttrsAttributeNames: Set<String>,
        val children: MutableList<Node> = mutableListOf()
    )

    /**
     * Describes one statically reconstructed Android attribute.
     * @param name the unqualified Android attribute name.
     * @param value the XML-compatible static or conservative fallback value.
     * @param nameElement the source PSI element that declares the attribute name.
     * @param valueElement the optional source PSI element that declares its value.
     * @param isValueStatic whether the value is safe for value-sensitive host Lint rules.
     */
    data class Attribute(
        val name: String,
        val value: String,
        val nameElement: PsiElement,
        val valueElement: PsiElement?,
        val isValueStatic: Boolean
    )
}