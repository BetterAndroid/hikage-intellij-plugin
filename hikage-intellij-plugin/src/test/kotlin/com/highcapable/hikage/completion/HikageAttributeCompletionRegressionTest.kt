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

/**
 * Verifies the runtime-gated Hikage attribute completion entry behavior.
 */
class HikageAttributeCompletionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies completing the real setter inserts an empty attribute-name argument. */
    fun testSetCompletionInsertsAttributeNameAndMovesCaretInsideIt() {
        installHikageTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        configureKotlinByText(
            "HikageAttributeSetCompletion.kt",
            """
            package sample

            import com.highcapable.hikage.core.attribute.HikageAttribute

            fun verify() {
                HikageAttribute {
                    se<caret>
                }
            }
            """.trimIndent()
        )

        val item = requireNotNull(myFixture.completeBasic()).first { candidate -> candidate.lookupString == "set" }
        selectLookupElement(item)

        myFixture.checkResult(
            """
            package sample

            import com.highcapable.hikage.core.attribute.HikageAttribute

            fun verify() {
                HikageAttribute {
                    set("<caret>")
                }
            }
            """.trimIndent()
        )
    }
}