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
package com.highcapable.hikage.project

import com.highcapable.hikage.generated.PluginProperties

/**
 * Maven coordinates used to identify Hikage projects.
 */
object Coordinates {

    /** The Maven group used by Hikage artifacts. */
    const val GROUP = PluginProperties.PROJECT_GROUP_NAME

    /** The BOM artifact that manages Hikage library versions. */
    const val BOM_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_BOM_MODULE_NAME

    /** The Hikage BOM version declared by this plugin build. */
    const val BOM_VERSION = PluginProperties.PROJECT_REFERENCE_HIKAGE_BOM_VERSION

    /** The full Maven coordinate prefix for hikage-bom. */
    const val BOM_MODULE = "$GROUP:$BOM_ARTIFACT"

    /** The versioned Hikage BOM coordinate declared by this plugin build. */
    const val BOM_DEPENDENCY = "$BOM_MODULE:$BOM_VERSION"

    /** The core runtime artifact required by Hikage projects. */
    const val CORE_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_CORE_MODULE_NAME

    /** The full Maven coordinate for hikage-core. */
    const val CORE_MODULE = "$GROUP:$CORE_ARTIFACT"

    /** The standard Hikage extension artifact. */
    const val EXTENSION_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_EXTENSION_MODULE_NAME

    /** The full Maven coordinate for hikage-extension. */
    const val EXTENSION_MODULE = "$GROUP:$EXTENSION_ARTIFACT"

    /** The Hikage extension artifact that integrates BetterAndroid components. */
    const val EXTENSION_BETTERANDROID_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_EXTENSION_BETTERANDROID_MODULE_NAME

    /** The full Maven coordinate for hikage-extension-betterandroid. */
    const val EXTENSION_BETTERANDROID_MODULE = "$GROUP:$EXTENSION_BETTERANDROID_ARTIFACT"

    /** The optional runtime attribute artifact. */
    const val RUNTIME_ATTRIBUTE_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_RUNTIME_ATTRIBUTE_MODULE_NAME

    /** The full Maven coordinate prefix for hikage-runtime-attribute. */
    const val RUNTIME_ATTRIBUTE_MODULE = "$GROUP:$RUNTIME_ATTRIBUTE_ARTIFACT"

    /** The standard platform-independent widget declaration artifact. */
    const val WIDGET_FOUNDATION_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_WIDGET_FOUNDATION_MODULE_NAME

    /** The full Maven coordinate for hikage-widget-foundation. */
    const val WIDGET_FOUNDATION_MODULE = "$GROUP:$WIDGET_FOUNDATION_ARTIFACT"

    /** The standard AndroidX widget declaration artifact. */
    const val WIDGET_ANDROIDX_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_WIDGET_ANDROIDX_MODULE_NAME

    /** The full Maven coordinate for hikage-widget-androidx. */
    const val WIDGET_ANDROIDX_MODULE = "$GROUP:$WIDGET_ANDROIDX_ARTIFACT"

    /** The standard Material widget declaration artifact. */
    const val WIDGET_MATERIAL_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_WIDGET_MATERIAL_MODULE_NAME

    /** The full Maven coordinate for hikage-widget-material. */
    const val WIDGET_MATERIAL_MODULE = "$GROUP:$WIDGET_MATERIAL_ARTIFACT"

    /** The Hikage Gradle plugin ID. */
    const val GRADLE_PLUGIN_ID = GROUP

    /** The implementation artifact used when the Gradle plugin must be added through a legacy classpath. */
    const val GRADLE_PLUGIN_ARTIFACT = PluginProperties.PROJECT_REFERENCE_HIKAGE_GRADLE_PLUGIN_MODULE_NAME

    /** The full Maven coordinate prefix for the Hikage Gradle plugin implementation. */
    const val GRADLE_PLUGIN_MODULE = "$GROUP:$GRADLE_PLUGIN_ARTIFACT"

    /** The Hikage Gradle plugin version declared by this plugin build. */
    const val GRADLE_PLUGIN_VERSION = PluginProperties.PROJECT_REFERENCE_HIKAGE_GRADLE_PLUGIN_VERSION

    /** The preferred Version Catalog alias for the Hikage Gradle plugin. */
    const val GRADLE_PLUGIN_ALIAS = "hikage"

    /** The preferred Version Catalog version alias for the Hikage Gradle plugin. */
    const val GRADLE_PLUGIN_VERSION_ALIAS = "hikagePlugin"

    /** The Maven group used by BetterAndroid artifacts. */
    const val BETTERANDROID_GROUP = PluginProperties.PROJECT_REFERENCE_BETTERANDROID_GROUP_NAME

    /** The BetterAndroid adapter component artifact detected by the Hikage recommendation. */
    const val BETTERANDROID_UI_COMPONENT_ADAPTER_ARTIFACT = PluginProperties.PROJECT_REFERENCE_BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE_NAME

    /** The full Maven coordinate for BetterAndroid's ui-component-adapter. */
    const val BETTERANDROID_UI_COMPONENT_ADAPTER_MODULE = "$BETTERANDROID_GROUP:$BETTERANDROID_UI_COMPONENT_ADAPTER_ARTIFACT"

    /** The standard runtime and widget dependencies installed by the Hikage recommendation. */
    val STANDARD_DEPENDENCY_MODULES = listOf(
        CORE_MODULE,
        EXTENSION_MODULE,
        WIDGET_FOUNDATION_MODULE,
        WIDGET_ANDROIDX_MODULE,
        WIDGET_MATERIAL_MODULE
    )
}