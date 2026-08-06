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
package com.highcapable.hikage.settings

import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.ViewConversionOption
import com.highcapable.hikage.settings.bundle.SettingsBundle
import com.highcapable.hikage.settings.service.SettingsService
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.ui.components.ActionLink
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList

/**
 * Verifies persisted defaults and the single-page Hikage settings contract.
 */
class SettingsRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies workspace switches and conversion options keep their documented defaults and remain independently mutable. */
    fun testWorkspaceSettingDefaultsAndMutations() {
        val settings = SettingsService.getInstance(project)

        assertTrue(settings.isDefaultLayoutParamsAutoCompletionEnabled)
        assertTrue(settings.isLayoutLookupPreviewEnabled)
        assertTrue(settings.isAttributeResourceReferencePreviewEnabled)
        assertEquals(ViewConversionOption.COMPATIBLE_MODE, settings.viewConversionOption)
        assertEquals(LayoutParamsConversionOption.COMPATIBLE_MODE, settings.layoutParamsConversionOption)
        assertTrue(settings.isAndroidLintMirrorEnabled)

        try {
            settings.isDefaultLayoutParamsAutoCompletionEnabled = false
            settings.isLayoutLookupPreviewEnabled = false
            settings.isAttributeResourceReferencePreviewEnabled = false
            settings.viewConversionOption = ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY
            settings.layoutParamsConversionOption = LayoutParamsConversionOption.LAYOUT_PARAMS_ONLY
            settings.isAndroidLintMirrorEnabled = false

            assertFalse(settings.isDefaultLayoutParamsAutoCompletionEnabled)
            assertFalse(settings.isLayoutLookupPreviewEnabled)
            assertFalse(settings.isAttributeResourceReferencePreviewEnabled)
            assertEquals(ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY, settings.viewConversionOption)
            assertEquals(LayoutParamsConversionOption.LAYOUT_PARAMS_ONLY, settings.layoutParamsConversionOption)
            assertFalse(settings.isAndroidLintMirrorEnabled)
        } finally {
            settings.isDefaultLayoutParamsAutoCompletionEnabled = true
            settings.isLayoutLookupPreviewEnabled = true
            settings.isAttributeResourceReferencePreviewEnabled = true
            settings.viewConversionOption = ViewConversionOption.COMPATIBLE_MODE
            settings.layoutParamsConversionOption = LayoutParamsConversionOption.COMPATIBLE_MODE
            settings.isAndroidLintMirrorEnabled = true
        }
    }

    /** Verifies that opening Hikage creates one page with the flat settings surface in the required group order. */
    fun testRootConfigurableContainsTheFlatSettingsSurface() {
        enableHikageRuntimeAttribute()
        val settings = SettingsService.getInstance(project)
        val configurable = RootConfigurable(project)
        val component = configurable.createComponent()
        configurable.reset()
        component.size = component.preferredSize
        component.layoutRecursively()
        val descendants = component.descendants()
        val buttonTexts = descendants.filterIsInstance<AbstractButton>()
            .filter(AbstractButton::isVisible)
            .mapNotNull(AbstractButton::getText)
            .filter(String::isNotBlank)
        val labelTexts = descendants.filterIsInstance<JLabel>().map(JLabel::getText)
        val comboBoxes = descendants.filterIsInstance<JComboBox<*>>()
        val viewAttributeOption = comboBoxes.single { comboBox ->
            comboBox.itemCount > 0 && comboBox.getItemAt(0) is ViewConversionOption
        }
        val layoutParamsOption = comboBoxes.single { comboBox ->
            comboBox.itemCount > 0 && comboBox.getItemAt(0) is LayoutParamsConversionOption
        }

        assertEquals("Hikage", configurable.displayName)
        assertTrue(SettingsBundle.message("settings.page.description") in labelTexts)
        assertEquals("XML Layout Conversion", SettingsBundle.message("settings.group.xml-layout-conversion"))
        assertTrue(
            labelTexts.indexOf(SettingsBundle.message("settings.group.hikage-attribute")) <
                labelTexts.indexOf(SettingsBundle.message("settings.group.xml-layout-conversion"))
        )
        assertTrue(
            labelTexts.indexOf(SettingsBundle.message("settings.group.xml-layout-conversion")) <
                labelTexts.indexOf(SettingsBundle.message("settings.group.android-lint"))
        )
        assertTrue(SettingsBundle.message("settings.group.xml-layout-conversion.view-option") in labelTexts)
        assertTrue(SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option") in labelTexts)
        assertEquals(
            setOf(
                SettingsBundle.message("settings.group.hikage-dsl.autofill-default-layout-params"),
                SettingsBundle.message("settings.group.hikage-dsl.layout-lookup-preview-enabled"),
                SettingsBundle.message("settings.group.hikage-attribute.resource-reference-preview-enabled"),
                SettingsBundle.message("settings.group.android-lint.mirror-enabled")
            ),
            buttonTexts.toSet()
        )
        assertEquals(ViewConversionOption.entries, viewAttributeOption.items())
        assertEquals(LayoutParamsConversionOption.entries, layoutParamsOption.items())
        assertEquals(
            listOf("Fully attributes", "Compatible mode", "Generate constructor only"),
            viewAttributeOption.renderedItems()
        )
        assertEquals(
            listOf("Fully attributes", "Compatible mode", "LayoutParams only"),
            layoutParamsOption.renderedItems()
        )
        assertContains(
            SettingsBundle.message("settings.group.xml-layout-conversion.view-option.help"),
            "<b>Compatible mode</b> prefers proven constructor writes"
        )
        assertContains(
            SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option.help"),
            "<b>Compatible mode</b> prefers an explicit <code>LayoutParams</code>"
        )
        assertEquals(ViewConversionOption.COMPATIBLE_MODE, viewAttributeOption.selectedItem)
        assertEquals(LayoutParamsConversionOption.COMPATIBLE_MODE, layoutParamsOption.selectedItem)
        assertTrue(viewAttributeOption.width > 0)
        assertEquals(viewAttributeOption.width, layoutParamsOption.width)
        assertTrue(viewAttributeOption.isEnabled)
        assertTrue(layoutParamsOption.isEnabled)
        assertFalse(descendants.filterIsInstance<ActionLink>().single().isVisible)

        try {
            viewAttributeOption.selectedItem = ViewConversionOption.FULLY_ATTRIBUTES
            layoutParamsOption.selectedItem = LayoutParamsConversionOption.FULLY_ATTRIBUTES
            configurable.apply()

            assertEquals(ViewConversionOption.FULLY_ATTRIBUTES, settings.viewConversionOption)
            assertEquals(LayoutParamsConversionOption.FULLY_ATTRIBUTES, settings.layoutParamsConversionOption)
        } finally {
            settings.viewConversionOption = ViewConversionOption.COMPATIBLE_MODE
            settings.layoutParamsConversionOption = LayoutParamsConversionOption.COMPATIBLE_MODE
        }

        configurable.disposeUIResources()
    }

    /** Verifies missing runtime attrs gray both conversion controls without rewriting their persisted defaults. */
    fun testMissingRuntimeAttributeDependencyGatesAttrsConversion() {
        val settings = SettingsService.getInstance(project)
        val configurable = RootConfigurable(project)
        val component = configurable.createComponent()
        configurable.reset()
        val descendants = component.descendants()
        val comboBoxes = descendants.filterIsInstance<JComboBox<*>>()
        val viewAttributeOption = comboBoxes.single { comboBox ->
            comboBox.itemCount > 0 && comboBox.getItemAt(0) is ViewConversionOption
        }
        val layoutParamsOption = comboBoxes.single { comboBox ->
            comboBox.itemCount > 0 && comboBox.getItemAt(0) is LayoutParamsConversionOption
        }
        val dependencyLink = descendants.filterIsInstance<ActionLink>().single()

        assertFalse(viewAttributeOption.isEnabled)
        assertFalse(layoutParamsOption.isEnabled)
        assertTrue(dependencyLink.isVisible)
        assertEquals(
            SettingsBundle.message("settings.group.xml-layout-conversion.add-runtime-attribute-dependency"),
            dependencyLink.text
        )
        assertEquals(ViewConversionOption.COMPATIBLE_MODE, settings.viewConversionOption)
        assertEquals(LayoutParamsConversionOption.COMPATIBLE_MODE, settings.layoutParamsConversionOption)
        assertEquals(
            ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY,
            settings.viewConversionOption.effectiveOption(false)
        )
        assertEquals(
            LayoutParamsConversionOption.LAYOUT_PARAMS_ONLY,
            settings.layoutParamsConversionOption.effectiveOption(false)
        )

        configurable.disposeUIResources()
    }

    private fun JComboBox<*>.items() = List(itemCount, ::getItemAt)

    private fun <T> JComboBox<T>.renderedItems() = List(itemCount) { index ->
        val component = renderer.getListCellRendererComponent(JList(), getItemAt(index), index, false, false)
        requireNotNull((component as? JLabel)?.text)
    }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) this@descendants.components.forEach { child ->
            addAll(child.descendants())
        }
    }

    private fun Component.layoutRecursively() {
        if (this !is Container) return
        doLayout()
        components.forEach { child -> child.layoutRecursively() }
    }
}