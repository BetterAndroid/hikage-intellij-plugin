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
 * This file is created by fankes on 2026/7/24.
 */
package com.highcapable.hikage.highlighting

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies Hikage call colors produced by the Kotlin K2 call-highlighting pass.
 */
class HikageCallHighlighterRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies performer membership, outer receivers, and the independent DSL color categories. */
    fun testK2CallHighlightingUsesResolvedSymbolsAndOuterPerformerScope() {
        installHikageTestApi()
        enableHikageProject()
        addProjectFile(
            "com/highcapable/hikage/core/layout/extension/ResourcesScope.kt",
            """
            package com.highcapable.hikage.core.layout.extension

            class ResourcesScope {
                fun text() = "text"
            }
            """.trimIndent()
        )
        val file = configureKotlinByText(
            "HikageCallHighlighting.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.HikageAttribute
            import com.highcapable.hikage.core.layout.LayoutParams
            import com.highcapable.hikage.core.layout.extension.ResourcesScope

            @Hikagable
            fun Hikage.Performer.Card() = Unit

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    Card()
                    scope("nested") { Card() }
                    LayoutParams()
                }
                Hikage.Performer().Card()
                ResourcesScope().text()
                HikageAttribute { set("text", "value") }
            }
            """.trimIndent()
        )

        val highlightedCalls = myFixture.doHighlighting().mapNotNull { highlight ->
            val key = highlight.type.attributesKey.externalName
            val text = file.text.substring(highlight.startOffset, highlight.endOffset)
            text to key
        }

        assertEquals(
            "Unexpected resolved-call highlights: $highlightedCalls",
            2,
            highlightedCalls.count { (text, key) ->
                text == "Card" && key == "HikagableCallTextAttributes"
            }
        )
        assertTrue(("LayoutParams" to "HikageLayoutParamsCallTextAttributes") in highlightedCalls)
        assertTrue(("text" to "HikageResourcesScopeCallTextAttributes") in highlightedCalls)
        assertTrue(("HikageAttribute" to "XML_NS_PREFIX") in highlightedCalls)
        assertTrue(("set" to "HikageAttributeSetCallTextAttributes") in highlightedCalls)
    }
}