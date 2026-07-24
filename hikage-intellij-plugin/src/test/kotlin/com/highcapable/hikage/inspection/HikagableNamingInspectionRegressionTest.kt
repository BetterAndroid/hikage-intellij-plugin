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
package com.highcapable.hikage.inspection

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies PascalCase diagnostics for Hikagable functions and direct Hikage factory properties.
 */
class HikagableNamingInspectionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies diagnostics, valid names, and the dedicated Rename actions. */
    fun testInvalidFunctionAndPropertyNamesAreReported() {
        installHikageTestApi()
        enableHikageProject()
        myFixture.enableInspections(HikagableNamingInspection())
        configureKotlinByText(
            "HikagableNaming.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun invalidFunction() = Unit

            @Hikagable
            fun ValidFunction() = Unit

            val invalidProperty = Hikagable {}
            val ValidProperty = Hikagable {}
            """.trimIndent()
        )

        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        val quickFixes = myFixture.getAllQuickFixes().map { action -> action.text }

        assertEquals(1, descriptions.count { description -> description == "Hikagable functions should start with an uppercase letter" })
        assertEquals(1, descriptions.count { description -> description == "Hikagable properties should start with an uppercase letter" })
        assertTrue("Rename Hikagable function" in quickFixes)
        assertTrue("Rename Hikagable property" in quickFixes)
    }

    /** Verifies that the Inspection contributes no visitor without the core dependency. */
    fun testInspectionIsDisabledByProjectGate() {
        installHikageTestApi()
        myFixture.enableInspections(HikagableNamingInspection())
        configureKotlinByText(
            "DisabledHikagableNaming.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable

            @Hikagable
            fun invalidFunction() = Unit
            """.trimIndent()
        )

        assertFalse(
            myFixture.doHighlighting().any { highlight ->
                highlight.description == "Hikagable functions should start with an uppercase letter"
            }
        )
    }
}