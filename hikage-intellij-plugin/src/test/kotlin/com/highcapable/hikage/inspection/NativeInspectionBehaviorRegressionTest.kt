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
 * Verifies representative native Hikage Inspection behavior and Quick Fixes.
 */
class NativeInspectionBehaviorRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies the annotation Quick Fix when the Hikagable factory and annotation imports share a name. */
    fun testMissingHikagableAnnotationIsReportedAndFixed() {
        installHikageTestApi()
        enableHikageProject()
        myFixture.enableInspections(HikagablePropagationInspection())
        addProjectFile(
            "com/highcapable/hikage/fixture/Child.kt",
            """
            package com.highcapable.hikage.fixture

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            @Hikagable
            fun Hikage.Performer.Child() = Unit
            """.trimIndent()
        )
        configureKotlinByText(
            "HikagablePropagation.kt",
            """
            package com.highcapable.hikage.fixture.consumer

            import com.highcapable.hikage.fixture.Child
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.annotation.Hikagable

            fun Hikage.Performer.wrapper() {
                Child()
            }
            """.trimIndent()
        )

        assertTrue(myFixture.doHighlighting().any { highlight ->
            highlight.description?.contains("must be marked") == true
        })
        val fix = myFixture.getAllQuickFixes().single { action ->
            action.text == "Add '@Hikagable' to 'wrapper'"
        }
        myFixture.launchAction(fix)

        assertContains(myFixture.file.text, "@Hikagable\nfun Hikage.Performer.wrapper()")
        assertFalse(myFixture.doHighlighting().any { highlight ->
            highlight.description?.contains("must be marked") == true
        })
    }
}