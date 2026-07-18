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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.model

import com.highcapable.hikage.generated.PluginProperties

/**
 * Maven coordinates used to identify Hikage projects.
 */
object Coordinates {

    /** The Maven group used by Hikage artifacts. */
    const val GROUP = PluginProperties.PROJECT_GROUP_NAME

    /** The BOM artifact that manages Hikage library versions. */
    const val BOM_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_BOM_MODULE_NAME

    /** The full Maven coordinate prefix for hikage-bom. */
    const val BOM_MODULE = "$GROUP:$BOM_ARTIFACT"

    /** The core runtime artifact required by Hikage projects. */
    const val CORE_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_CORE_MODULE_NAME

    /** The optional runtime attribute artifact. */
    const val RUNTIME_ATTRIBUTE_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_RUNTIME_ATTRIBUTE_MODULE_NAME

    /** The full Maven coordinate prefix for hikage-runtime-attribute. */
    const val RUNTIME_ATTRIBUTE_MODULE = "$GROUP:$RUNTIME_ATTRIBUTE_ARTIFACT"
}