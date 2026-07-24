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
package com.highcapable.hikage.completion

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Verifies performer-scope filtering and ranking in the real Kotlin completion stream.
 */
class HikagableCompletionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies that a Hikagable function ranks before a same-named class without removing the class. */
    fun testNearestPerformerRanksFunctionAndKeepsClassifier() {
        installHikageTestApi()
        enableHikageProject()
        configureKotlinByText(
            "NearestPerformerCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            class Card

            @Hikagable
            fun Hikage.Performer.Card() = Unit

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    Car<caret>
                }
            }
            """.trimIndent()
        )

        val items = requireNotNull(myFixture.completeBasic()).filter { item -> item.lookupString == "Card" }
        val details = items.joinToString { item ->
            "${item.psiElement?.javaClass?.name}:${item.getUserData(HikagableCompletionContributor.functionLookupKey)}"
        }
        val functionIndex = items.indexOfFirst { item ->
            item.getUserData(HikagableCompletionContributor.functionLookupKey) == true
        }
        val classifierIndex = items.indexOfFirst { item -> item.psiElement is KtClassOrObject }

        assertTrue("Expected a prioritized function candidate: $details", functionIndex >= 0)
        assertTrue("Expected the same-named classifier candidate: $details", classifierIndex >= 0)
        assertTrue("Expected the function before the classifier: $details", functionIndex < classifierIndex)
    }

    /** Verifies that a nearer non-performer receiver removes only Hikagable function candidates. */
    fun testOuterPerformerHidesFunctionButKeepsClassifier() {
        installHikageTestApi()
        enableHikageProject()
        configureKotlinByText(
            "OuterPerformerCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            class Card

            @Hikagable
            fun Hikage.Performer.Card() = Unit

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    scope("nested") {
                        Car<caret>
                    }
                }
            }
            """.trimIndent()
        )

        val items = requireNotNull(myFixture.completeBasic()).filter { item -> item.lookupString == "Card" }

        assertTrue(items.any { item -> item.psiElement is KtClassOrObject })
        assertFalse(items.any { item -> item.getUserData(HikagableCompletionContributor.functionLookupKey) == true })
    }
}