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
package com.highcapable.hikage.settings

import com.highcapable.hikage.settings.bundle.SettingsBundle
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project

/**
 * Provides the root Hikage settings page.
 */
class RootConfigurable(private val project: Project) : SearchableConfigurable.Parent.Abstract(), ConfigurableGroup {

    private companion object {
        const val SETTINGS_ID = "com.highcapable.hikage.settings"
    }

    override fun getId() = SETTINGS_ID
    override fun getDisplayName() = SettingsBundle.message("settings.page.name")
    override fun getDescription() = SettingsBundle.message("settings.page.description")

    override fun buildConfigurables() = PROJECT_CONFIGURABLE
        .getExtensions(project)
        .asSequence()
        .filter { extension -> extension.parentId == SETTINGS_ID }
        .mapNotNull { extension -> extension.createConfigurable() }
        .toList()
        .toTypedArray()
}