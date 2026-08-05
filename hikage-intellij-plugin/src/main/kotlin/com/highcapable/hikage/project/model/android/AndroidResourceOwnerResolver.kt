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
 * This file is created by fankes on 2026/8/2.
 */
package com.highcapable.hikage.project.model.android

import com.android.ide.common.resources.ResourceRepository
import com.android.tools.dom.attrs.AttributeDefinition
import com.highcapable.hikage.utils.extension.failOpen

/**
 * Resolves the Android resource package that owns an attribute definition.
 */
object AndroidResourceOwnerResolver {

    /**
     * Resolves the declaration package of [definition] through [resources].
     * @return the unique package name, or null when package ownership is ambiguous.
     */
    fun resolvePackageName(
        definition: AttributeDefinition,
        resources: ResourceRepository
    ): String? {
        val namespacePackageName = definition.resourceReference.namespace.packageName?.takeIf(String::isNotBlank)
        val items = failOpen { resources.getResources(definition.resourceReference) }.orEmpty()
        val ownerItems = definition.libraryName?.let { libraryName ->
            items.filter { item -> item.libraryName == libraryName }
        } ?: items

        return namespacePackageName ?: ownerItems.mapNotNull { item ->
            failOpen { item.repository.packageName?.takeIf(String::isNotBlank) }
        }.distinct().singleOrNull()
    }
}