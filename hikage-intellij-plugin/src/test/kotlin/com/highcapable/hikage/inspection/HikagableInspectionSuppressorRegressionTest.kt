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

import com.highcapable.hikage.inspection.suppressor.HikagableInspectionSuppressor
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Verifies Kotlin naming suppression for Hikagable functions and Hikage layout properties.
 */
class HikagableInspectionSuppressorRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies supported naming tools and rejects unrelated declarations and tool IDs. */
    fun testNamingSuppressionUsesResolvedHikageDeclarations() {
        installHikageTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "NamingSuppression.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun sample() = Unit

            private val sampleLayout = Hikagable {}
            private val ordinary = Unit
            """.trimIndent()
        )
        val function = file.declarations.filterIsInstance<KtNamedFunction>().single()
        val properties = file.declarations.filterIsInstance<KtProperty>().associateBy(KtProperty::getName)
        val suppressor = HikagableInspectionSuppressor()

        assertTrue(suppressor.isSuppressedFor(requireNotNull(function.nameIdentifier), "FunctionName"))
        assertTrue(suppressor.isSuppressedFor(requireNotNull(properties.getValue("sampleLayout").nameIdentifier), "PropertyName"))
        assertTrue(suppressor.isSuppressedFor(requireNotNull(properties.getValue("sampleLayout").nameIdentifier), "PrivatePropertyName"))
        assertFalse(suppressor.isSuppressedFor(requireNotNull(properties.getValue("ordinary").nameIdentifier), "PropertyName"))
        assertFalse(suppressor.isSuppressedFor(requireNotNull(function.nameIdentifier), "UnusedSymbol"))
    }

    /** Verifies that the project dependency gate remains authoritative for suppression. */
    fun testNamingSuppressionStaysDisabledWithoutCoreDependency() {
        installHikageTestApi()
        val file = configureKotlinByText(
            "DisabledNamingSuppression.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.Hikagable

            @Hikagable
            fun sample() = Unit
            """.trimIndent()
        )
        val function = file.declarations.filterIsInstance<KtNamedFunction>().single()

        assertFalse(
            HikagableInspectionSuppressor().isSuppressedFor(
                requireNotNull(function.nameIdentifier),
                "FunctionName"
            )
        )
    }
}