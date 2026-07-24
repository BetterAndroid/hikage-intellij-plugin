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

import com.highcapable.hikage.settings.bundle.SettingsBundle
import com.highcapable.hikage.settings.service.SettingsService
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

/**
 * Verifies persisted defaults and the single-page Hikage settings contract.
 */
class SettingsRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies all workspace switches default to enabled and remain independently mutable. */
    fun testWorkspaceSwitchDefaultsAndMutations() {
        val settings = SettingsService.getInstance(project)

        assertTrue(settings.isDefaultLayoutParamsAutoCompletionEnabled)
        assertTrue(settings.isLayoutLookupPreviewEnabled)
        assertTrue(settings.isAttributeResourceReferencePreviewEnabled)
        assertTrue(settings.isAndroidLintMirrorEnabled)

        try {
            settings.isDefaultLayoutParamsAutoCompletionEnabled = false
            settings.isLayoutLookupPreviewEnabled = false
            settings.isAttributeResourceReferencePreviewEnabled = false
            settings.isAndroidLintMirrorEnabled = false

            assertFalse(settings.isDefaultLayoutParamsAutoCompletionEnabled)
            assertFalse(settings.isLayoutLookupPreviewEnabled)
            assertFalse(settings.isAttributeResourceReferencePreviewEnabled)
            assertFalse(settings.isAndroidLintMirrorEnabled)
        } finally {
            settings.isDefaultLayoutParamsAutoCompletionEnabled = true
            settings.isLayoutLookupPreviewEnabled = true
            settings.isAttributeResourceReferencePreviewEnabled = true
            settings.isAndroidLintMirrorEnabled = true
        }
    }

    /** Verifies that opening Hikage creates one page with the description and all four switches. */
    fun testRootConfigurableContainsTheFlatSettingsSurface() {
        val configurable = RootConfigurable(project)
        val component = configurable.createComponent()
        val descendants = component.descendants()
        val buttonTexts = descendants.filterIsInstance<AbstractButton>().map(AbstractButton::getText)
        val labelTexts = descendants.filterIsInstance<JLabel>().map(JLabel::getText)

        assertEquals("Hikage", configurable.displayName)
        assertTrue(SettingsBundle.message("settings.page.description") in labelTexts)
        assertEquals(
            setOf(
                SettingsBundle.message("settings.group.hikage-dsl.autofill-default-layout-params"),
                SettingsBundle.message("settings.group.hikage-dsl.layout-lookup-preview-enabled"),
                SettingsBundle.message("settings.group.hikage-attribute.resource-reference-preview-enabled"),
                SettingsBundle.message("settings.group.android-lint.mirror-enabled")
            ),
            buttonTexts.toSet()
        )

        configurable.disposeUIResources()
    }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) this@descendants.components.forEach { child ->
            addAll(child.descendants())
        }
    }
}