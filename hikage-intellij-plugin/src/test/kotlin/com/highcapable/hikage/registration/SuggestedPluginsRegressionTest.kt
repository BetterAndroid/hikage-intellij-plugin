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
package com.highcapable.hikage.registration

import com.highcapable.hikage.project.Coordinates
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Verifies the official dependency-based Suggested Plugins descriptor.
 */
class SuggestedPluginsRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies the loaded Java dependency feature is versionless and targets hikage-core. */
    fun testDependencySupportRegistrationUsesVersionlessCoreCoordinate() {
        val registration = ExtensionPointName.create<Any>("com.intellij.dependencySupport")
            .extensionList
            .single { extension ->
                extension.javaClass.getField("coordinate").get(extension) == Coordinates.CORE_MODULE
            }

        assertEquals("java", registration.javaClass.getField("kind").get(registration))
        assertEquals(Coordinates.CORE_MODULE, registration.javaClass.getField("coordinate").get(registration))
        assertEquals("Hikage", registration.javaClass.getField("displayName").get(registration))
        assertEquals(1, Coordinates.CORE_MODULE.count { character -> character == ':' })
    }
}