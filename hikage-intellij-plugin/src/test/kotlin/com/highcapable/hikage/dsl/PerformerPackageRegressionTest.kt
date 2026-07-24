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
package com.highcapable.hikage.dsl

import com.highcapable.hikage.dsl.builder.PerformerSourceBuilder
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerSpec
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies Java package presentation and diagnostic filtering for IDE-only performer sources.
 */
class PerformerPackageRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies the stable generated package prefix and rejects unrelated or unknown descendants. */
    fun testPackageFinderExposesOnlyKnownHikagePackages() {
        enableHikageProject()
        val finder = PerformerPackageElementFinder(project)

        assertNotNull(finder.findPackage(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX))
        assertNull(finder.findPackage("com.highcapable.hikage.other"))
        assertNull(finder.findPackage("${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.unknown"))
    }

    /** Verifies that only marked Kotlin stubs in the generated package suppress ordinary diagnostics. */
    fun testProblemHighlightFilterRecognizesGeneratedStubMarker() {
        enableHikageProject()
        val declaration = PerformerDeclaration(
            spec = PerformerSpec(null, attrs = true, init = true, performer = false),
            declaration = requireNotNull(ViewDeclaration.from("sample.widgets.Widget", null, false)),
            source = PerformerDeclaration.Source.ANNOTATION
        )
        val generatedFile = configureKotlinByText(
            "Widget.kt",
            PerformerSourceBuilder.createSource(declaration)
        )
        val filter = PerformerResolveProblemHighlightFilter()

        assertFalse(filter.shouldHighlight(generatedFile))

        val ordinaryFile = configureKotlinByText(
            "OrdinaryWidget.kt",
            """
            package com.highcapable.hikage.widget.sample.widgets

            fun OrdinaryWidget() = Unit
            """.trimIndent()
        )
        assertTrue(filter.shouldHighlight(ordinaryFile))
    }
}