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

import com.highcapable.hikage.convert.action.ConvertSelectedXmlLayoutsAction
import com.highcapable.hikage.convert.action.CopyAsHikagablePropertyAction
import com.highcapable.hikage.convert.action.CopyAsHikageBuilderAction
import com.highcapable.hikage.convert.action.CopyAsPerformerFragmentAction
import com.highcapable.hikage.convert.action.GenerateKotlinFileAction
import com.highcapable.hikage.convert.action.QuickXmlLayoutConversionAction
import com.highcapable.hikage.convert.action.XmlLayoutConversionActionGroup
import com.highcapable.hikage.convert.output.PerformerSnippetPasteProcessor
import com.highcapable.hikage.generated.PluginProperties
import com.highcapable.hikage.inspection.HikagableNamingInspection
import com.highcapable.hikage.inspection.HikageLayoutInspection
import com.highcapable.hikage.mirror.lint.AndroidLintInspection
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager

/**
 * Verifies that the built plugin descriptor loads its split Inspection registrations.
 */
class PluginRegistrationRegressionTest : HikageCodeInsightTestCase() {

    private companion object {
        const val HIKAGE_GROUP_ID = "com.highcapable.hikage.convert.xmlLayout"
        const val COPY_PROPERTY_ID = "com.highcapable.hikage.convert.copyAsHikagableProperty"
        const val COPY_BUILDER_ID = "com.highcapable.hikage.convert.copyAsHikageBuilder"
        const val COPY_FRAGMENT_ID = "com.highcapable.hikage.convert.copyAsPerformerFragment"
        const val GENERATE_FILE_ID = "com.highcapable.hikage.convert.generateKotlinFile"
        const val BATCH_ID = "com.highcapable.hikage.convert.convertSelectedXmlLayouts"
        const val QUICK_ID = "com.highcapable.hikage.convert.quickXmlLayoutConversion"
        const val CONVERSION_NOTIFICATION_GROUP_ID = "Hikage XML Conversion"
    }

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

    /** Verifies the conversion descriptor loads every stable action ID and required menu placement. */
    fun testXmlConversionActionsAndMenusAreRegistered() {
        val actionManager = ActionManager.getInstance()
        val expectedActions = mapOf(
            HIKAGE_GROUP_ID to classOf<XmlLayoutConversionActionGroup>(),
            COPY_PROPERTY_ID to classOf<CopyAsHikagablePropertyAction>(),
            COPY_BUILDER_ID to classOf<CopyAsHikageBuilderAction>(),
            COPY_FRAGMENT_ID to classOf<CopyAsPerformerFragmentAction>(),
            GENERATE_FILE_ID to classOf<GenerateKotlinFileAction>(),
            BATCH_ID to classOf<ConvertSelectedXmlLayoutsAction>(),
            QUICK_ID to classOf<QuickXmlLayoutConversionAction>()
        )
        expectedActions.forEach { (actionId, actionClass) ->
            assertTrue("Action '$actionId' was not registered as ${actionClass.name}.", actionClass.isInstance(actionManager.getAction(actionId)))
        }
        val snippetPresentation = requireNotNull(actionManager.getAction(COPY_FRAGMENT_ID)).templatePresentation
        assertEquals("Copy as Performer Snippet", snippetPresentation.text)
        assertEquals("Copy the selected XML layout as a Performer snippet", snippetPresentation.description)
        val generateFilePresentation = requireNotNull(actionManager.getAction(GENERATE_FILE_ID)).templatePresentation
        assertEquals("Generate HikageBuilder File…", generateFilePresentation.text)
        assertEquals(
            "Generate a HikageBuilder source file from the selected XML layout",
            generateFilePresentation.description
        )
        assertEquals(
            "Generate HikageBuilder source files from the complete XML layout selection",
            requireNotNull(actionManager.getAction(BATCH_ID)).templatePresentation.description
        )
        assertEquals(
            "Quick XML Layout Conversion",
            requireNotNull(actionManager.getAction(QUICK_ID)).templatePresentation.text
        )
        assertEquals("Hikage", requireNotNull(actionManager.getAction(HIKAGE_GROUP_ID)).templatePresentation.text)

        assertEquals(
            listOf(COPY_FRAGMENT_ID, COPY_PROPERTY_ID, COPY_BUILDER_ID, BATCH_ID, GENERATE_FILE_ID),
            actionManager.childActionIds(HIKAGE_GROUP_ID)
        )
        assertFalse(QUICK_ID in actionManager.childActionIds(HIKAGE_GROUP_ID))
        assertTrue(HIKAGE_GROUP_ID in actionManager.childActionIds("ToolsMenu"))
        assertTrue(HIKAGE_GROUP_ID in actionManager.childActionIds("EditorPopupMenu"))
        assertTrue(HIKAGE_GROUP_ID in actionManager.childActionIds("ProjectViewPopupMenu"))
        assertFalse(HIKAGE_GROUP_ID in actionManager.childActionIds("RefactoringMenu"))
        assertFalse(BATCH_ID in actionManager.childActionIds("RefactoringMenu"))
        assertFalse(BATCH_ID in actionManager.childActionIds("ProjectViewPopupMenu"))
        assertNotNull(
            NotificationGroupManager.getInstance().getNotificationGroup(CONVERSION_NOTIFICATION_GROUP_ID)
        )
    }

    /** Verifies the snippet import-restoration processor is loaded from the conversion descriptor. */
    fun testSnippetPasteProcessorIsRegistered() {
        assertTrue(CopyPastePostProcessor.EP_NAME.extensionList.any { processor ->
            processor is PerformerSnippetPasteProcessor
        })
    }

    /** Verifies a conflicting Android Studio baseline candidate leaves quick conversion available but unbound. */
    fun testQuickConversionRemainsUnboundWhenBaselineCandidateConflicts() {
        val keymapManager = requireNotNull(KeymapManager.getInstance())
        val standardShortcut = KeyboardShortcut.fromString("ctrl alt shift H")
        val macShortcut = KeyboardShortcut.fromString("meta alt shift H")
        val standardKeymaps = listOf(
            KeymapManager.DEFAULT_IDEA_KEYMAP,
            KeymapManager.X_WINDOW_KEYMAP,
            KeymapManager.GNOME_KEYMAP,
            KeymapManager.KDE_KEYMAP
        )
        val macKeymaps = listOf(KeymapManager.MAC_OS_X_KEYMAP, KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP)
        (standardKeymaps.map { keymapName -> keymapName to standardShortcut } +
            macKeymaps.map { keymapName -> keymapName to macShortcut }).forEach { (keymapName, candidate) ->
            val keymap = requireNotNull(keymapManager.getKeymap(keymapName))
            assertTrue(
                "$candidate should keep its Android Studio baseline conflict visible in $keymapName.",
                "PopupHector" in keymap.getConflicts(QUICK_ID, candidate)
            )
            assertTrue("Quick conversion should remain unbound in $keymapName.", keymap.getShortcuts(QUICK_ID).isEmpty())
        }
    }

    private fun ActionManager.childActionIds(groupId: String) =
        (getAction(groupId) as DefaultActionGroup).getChildren(this).mapNotNull(::getId)
}