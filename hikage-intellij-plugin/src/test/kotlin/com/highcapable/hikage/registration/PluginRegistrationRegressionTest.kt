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

import com.highcapable.hikage.generated.PluginProperties
import com.highcapable.hikage.inspection.HikagableNamingInspection
import com.highcapable.hikage.inspection.HikageLayoutInspection
import com.highcapable.hikage.mirror.lint.AndroidLintInspection
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Verifies that the built plugin descriptor loads its split Inspection registrations.
 */
class PluginRegistrationRegressionTest : HikageCodeInsightTestCase() {

    fun testPluginDescriptorAndInspectionIncludesAreLoaded() {
        val pluginId = PluginId.getId(PluginProperties.PROJECT_PLUGIN_ID)
        val descriptor = PluginManagerCore.getPlugin(pluginId)
        assertNotNull("The Hikage plugin descriptor was not loaded by the test platform.", descriptor)
        if (descriptor == null) return

        val inspections = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { extension -> extension.pluginDescriptor.pluginId == pluginId }
            .associateBy { extension -> extension.shortName }

        assertEquals("Unexpected number of Hikage Inspection registrations.", 51, inspections.size)
        assertTrue(
            inspections.getValue("HikagableNaming").instantiateTool() is HikagableNamingInspection
        )
        assertTrue(
            inspections.getValue("EmptyHikageLayoutId").instantiateTool() is
                HikageLayoutInspection.EmptyHikageLayoutId
        )
        assertTrue(
            inspections.getValue("AndroidLintMirrorContentDescription").instantiateTool() is
                AndroidLintInspection.ContentDescriptionInspection
        )
    }

    /** Verifies the complete native and Android Lint mirror registration split. */
    fun testInspectionRegistrationCountsStayPartitionedByCapability() {
        val pluginId = PluginId.getId(PluginProperties.PROJECT_PLUGIN_ID)
        val inspections = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { extension -> extension.pluginDescriptor.pluginId == pluginId }
        val mirrorInspections = inspections.filter { extension ->
            extension.shortName.startsWith("AndroidLintMirror")
        }
        val nativeInspections = inspections - mirrorInspections.toSet()

        assertEquals(28, nativeInspections.size)
        assertEquals(23, mirrorInspections.size)
        assertEquals(51, inspections.map(LocalInspectionEP::getShortName).distinct().size)
    }
}