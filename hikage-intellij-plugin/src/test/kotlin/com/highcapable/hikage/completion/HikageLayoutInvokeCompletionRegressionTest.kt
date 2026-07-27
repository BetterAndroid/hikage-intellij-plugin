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
 * This file is created by fankes on 2026/7/27.
 */
package com.highcapable.hikage.completion

import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies context-aware layout `invoke` imports from performer-scope completion.
 */
class HikageLayoutInvokeCompletionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies that completing a delegate value imports its context-aware `invoke` operator. */
    fun testDelegateCompletionAddsInvokeImport() {
        installHikageTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "DelegateInvokeCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage

            val DelegateLayout = Hikage.Delegate()

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    DelegateLay<caret>
                }
            }
            """.trimIndent()
        )

        completeAndSelect("DelegateLayout")
        myFixture.type("()")

        assertContains(file.text, "import ${HikageSymbols.HIKAGE_LAYOUT_INVOKE_FUNCTION}")
        assertContains(file.text, "DelegateLayout()")
    }

    /** Verifies that completing a builder object imports its context-aware `invoke` operator. */
    fun testBuilderCompletionAddsInvokeImport() {
        installHikageTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "BuilderInvokeCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.builder.HikageBuilder

            object BuilderLayout : HikageBuilder {
                override fun build() = Hikage.Delegate()
            }

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    BuilderLay<caret>
                }
            }
            """.trimIndent()
        )

        completeAndSelect("BuilderLayout")
        myFixture.type("()")

        assertContains(file.text, "import ${HikageSymbols.HIKAGE_LAYOUT_INVOKE_FUNCTION}")
        assertContains(file.text, "BuilderLayout()")
    }

    /** Verifies that an outer performer behind a nearer receiver does not trigger the import. */
    fun testOuterPerformerCompletionDoesNotAddInvokeImport() {
        installHikageTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "OuterPerformerInvokeCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage

            val DelegateLayout = Hikage.Delegate()

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    scope("nested") {
                        DelegateLay<caret>
                    }
                }
            }
            """.trimIndent()
        )

        completeAndSelect("DelegateLayout")

        assertFalse(file.text.contains("import ${HikageSymbols.HIKAGE_LAYOUT_INVOKE_FUNCTION}"))
    }

    private fun completeAndSelect(lookupString: String) {
        val items = myFixture.completeBasic()
        if (items == null) {
            val caretOffset = myFixture.editor.caretModel.offset
            val insertedText = myFixture.editor.document.charsSequence
                .subSequence(caretOffset - lookupString.length, caretOffset)
                .toString()
            assertEquals("Expected the only completion item to be inserted.", lookupString, insertedText)
            return
        }

        val item = items.firstOrNull { element -> element.lookupString == lookupString }
        assertNotNull("Expected completion item '$lookupString'.", item)
        item?.let(::selectLookupElement)
    }
}