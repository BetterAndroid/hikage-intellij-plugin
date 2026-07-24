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

import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies the runtime-attribute dependency gate and its dedicated diagnostics.
 */
class HikageAttributeGateRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies an unavailable Gradle model fails closed instead of reporting a false missing dependency. */
    fun testUnavailableGradleModelDoesNotReportMissingRuntimeAttributeDependency() {
        installHikageTestApi()
        enableHikageProject()
        addProjectFile(
            "sample/AttributeView.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.AttributeScope

            @Hikagable
            fun Hikage.Performer.AttributeView(attrs: AttributeScope.() -> Unit = {}) = Unit
            """.trimIndent()
        )
        myFixture.enableInspections(HikageAttributeInspection.MissingHikageRuntimeAttributeDependency())
        configureKotlinByText(
            "MissingRuntimeAttribute.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage

            fun verify() = Hikage.create {
                AttributeView {
                    set("android:text", "Test")
                }
                AttributeView {}
            }
            """.trimIndent()
        )

        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        assertFalse(descriptions.any { description -> description.contains("runtime attribute dependency") })
        assertTrue(myFixture.getAllQuickFixes().isEmpty())
    }

    /** Verifies adding the runtime capability enables regular attrs diagnostics and withdraws the missing-dependency issue. */
    fun testRuntimeAttributeGateSwitchesInspectionFamilies() {
        installHikageTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        myFixture.enableInspections(
            HikageAttributeInspection.MissingHikageRuntimeAttributeDependency(),
            HikageAttributeInspection.InvalidHikageAttributeColorValue()
        )
        val file = configureKotlinByText(
            "RuntimeAttributeGate.kt",
            """
            package sample

            import com.highcapable.hikage.core.attribute.HikageAttribute

            fun verify() = HikageAttribute {
                set("android:textColor", "#12")
            }
            """.trimIndent()
        )

        assertTrue(HikageRuntimeAttributeGate.isEnabled(file))
        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        assertTrue(descriptions.any { description -> description.contains("must be #RGB") })
        assertFalse(descriptions.any { description -> description.contains("runtime attribute dependency") })
    }
}