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

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Verifies the completion-only default `LayoutParams()` argument insertion contract.
 */
class DefaultLayoutParamsCompletionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies dynamic parameter naming, multiline insertion, and import creation. */
    fun testBlankCallReceivesDefaultLayoutParamsArgument() {
        installHikageTestApi()
        enableHikageProject()
        addProjectFile(
            "sample/CardPerformer.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.layout.LayoutParams

            @Hikagable
            fun Hikage.Performer.Card(params: LayoutParams? = null) = Unit
            """.trimIndent()
        )
        configureKotlinByText(
            "DefaultLayoutParamsInsertion.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    Car<caret>
                }
            }
            """.trimIndent()
        )

        myFixture.completeBasic()?.first { candidate -> candidate.lookupString == "Card" }
            ?.let { item -> selectLookupElement(item) }

        myFixture.checkResult(
            """
            package sample

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.layout.LayoutParams

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    Card(
                        params = LayoutParams()
                    )
                }
            }
            """.trimIndent()
        )
    }

    /** Verifies that replacing a callee never mutates a non-blank argument list. */
    fun testExistingArgumentsRemainUntouched() {
        installHikageTestApi()
        enableHikageProject()
        addProjectFile(
            "sample/ExistingArgumentsPerformer.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.layout.LayoutParams

            @Hikagable
            fun Hikage.Performer.Card(value: String, params: LayoutParams? = null) = Unit
            """.trimIndent()
        )
        configureKotlinByText(
            "ExistingArgumentsInsertion.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage

            fun <T> scope(receiver: T, block: T.() -> Unit) = block(receiver)

            fun verify() {
                scope(Hikage.Performer()) {
                    Car<caret>("existing")
                }
            }
            """.trimIndent()
        )

        myFixture.completeBasic()?.first { candidate -> candidate.lookupString == "Card" }
            ?.let { item -> selectLookupElement(item) }

        assertContains(myFixture.file.text, "Card(\"existing\")")
        assertFalse(myFixture.file.text.contains("params = LayoutParams()"))
    }

    /** Verifies that the special `Layout` performer remains excluded from autofill. */
    fun testLayoutFunctionIsExcludedFromDefaultArgumentCompletion() {
        installHikageTestApi()
        val file = configureKotlinByText(
            "LayoutAutofillExclusion.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.layout.LayoutParams

            @Hikagable
            fun Hikage.Performer.Layout(lparams: LayoutParams? = null) = Unit
            """.trimIndent()
        )
        val function = file.declarations.filterIsInstance<KtNamedFunction>().single()

        assertFalse(DeclarationMatcher.shouldCompleteDefaultLayoutParams(function))
    }
}