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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.intellij.settings

import com.highcapable.hikage.intellij.settings.bundle.SettingsBundle
import com.highcapable.hikage.intellij.settings.service.SettingsService
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Provides Hikage completion settings.
 */
class CompletionConfigurable(project: Project) : SearchableConfigurable {

    private companion object {
        const val SETTINGS_ID = "com.highcapable.hikage.intellij.settings.completion"
    }

    private val settings = SettingsService.getInstance(project)
    private var settingsPanel: DialogPanel? = null

    override fun getId() = SETTINGS_ID
    override fun getDisplayName() = SettingsBundle.message("settings.page.completion")

    override fun isModified() = settingsPanel?.isModified() == true

    override fun createComponent(): JComponent {
        val panel = panel {
            group(SettingsBundle.message("settings.page.completion.group.performer")) {
                row {
                    checkBox(SettingsBundle.message("settings.page.completion.group.performer.autofill-default-layout-params"))
                        .bindSelected(settings::isDefaultLayoutParamsAutoCompletionEnabled)
                        .contextHelp(SettingsBundle.message("settings.page.completion.group.performer.autofill-default-layout-params.help"))
                }
            }
        }
        settingsPanel = panel
        return panel
    }

    override fun apply() {
        settingsPanel?.apply()
    }

    override fun reset() {
        settingsPanel?.reset()
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}