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
package com.highcapable.hikage.project

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies Maven-coordinate detection and root-model cache invalidation for [ProjectGate].
 */
class ProjectGateRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies that only the exact core coordinate enables the project feature gate. */
    fun testExactCoreCoordinateEnablesGateAfterRootChange() {
        val gate = ProjectGate.from(project)

        assertFalse(gate.isEnabled())
        assertEquals("disabled", gate.runIfEnabled("disabled") { "enabled" })

        addMavenLibrary("${Coordinates.GROUP}:not-${Coordinates.CORE_ARTIFACT}")
        assertFalse(gate.isEnabled())

        enableHikageProject()
        assertTrue(gate.isEnabled())
        assertEquals("enabled", gate.runIfEnabled("disabled") { "enabled" })
    }

    /** Verifies the BetterAndroid bridge is required only for the exact adapter coordinate while the bridge is absent. */
    fun testBetterAndroidAdapterRequiresItsMissingHikageExtension() {
        val dependencyService = GradleDependencyService.getInstance(project)
        fun requiresBridge() = dependencyService.requiresDependency(
            module,
            Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE,
            Coordinates.EXTENSION_BETTERANDROID_MODULE
        )

        assertFalse(requiresBridge())
        addMavenLibrary("${Coordinates.BETTERANDROID_GROUP}:not-${Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_ARTIFACT}")
        assertFalse(requiresBridge())

        addMavenLibrary(Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE)
        assertTrue(requiresBridge())

        addMavenLibrary(Coordinates.EXTENSION_BETTERANDROID_MODULE)
        assertFalse(requiresBridge())
    }

    /** Verifies that standard recommendation coordinates retain their BOM-managed shape. */
    fun testStandardCoordinatesRemainVersionlessBehindTheBom() {
        assertEquals("${Coordinates.GROUP}:${Coordinates.BOM_ARTIFACT}", Coordinates.BOM_MODULE)
        assertTrue(Coordinates.BOM_DEPENDENCY.startsWith("${Coordinates.BOM_MODULE}:"))
        assertEquals(Coordinates.CORE_MODULE, Coordinates.STANDARD_DEPENDENCY_MODULES.first())
        assertTrue(Coordinates.STANDARD_DEPENDENCY_MODULES.all { coordinate -> coordinate.count { it == ':' } == 1 })
        assertFalse(Coordinates.EXTENSION_BETTERANDROID_MODULE in Coordinates.STANDARD_DEPENDENCY_MODULES)
        assertEquals(
            "${Coordinates.BETTERANDROID_GROUP}:${Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_ARTIFACT}",
            Coordinates.BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE
        )
        assertEquals(
            "${Coordinates.GROUP}:${Coordinates.EXTENSION_BETTERANDROID_ARTIFACT}",
            Coordinates.EXTENSION_BETTERANDROID_MODULE
        )
    }
}