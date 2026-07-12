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
package com.highcapable.hikage.intellij.settings.service

import com.highcapable.hikage.generated.PluginProperties
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Stores project-level Hikage plugin settings.
 */
@Service(Service.Level.PROJECT)
@State(
    name = PluginProperties.PROJECT_SETTINGS_SERVICE_CLASS_NAME,
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class SettingsService : SerializablePersistentStateComponent<SettingsService.State>(State()) {

    companion object {

        /**
         * Returns the Hikage settings service for [project].
         */
        fun getInstance(project: Project) = project.service<SettingsService>()
    }

    /**
     * Persistent Hikage settings state.
     */
    class State : BaseState() {
        var isDefaultLayoutParamsAutoCompletionEnabled by property(true)
    }

    /**
     * Returns or updates whether completion should autofill the default `LayoutParams()` argument.
     */
    var isDefaultLayoutParamsAutoCompletionEnabled
        get() = state.isDefaultLayoutParamsAutoCompletionEnabled
        set(value) {
            state.isDefaultLayoutParamsAutoCompletionEnabled = value
        }
}