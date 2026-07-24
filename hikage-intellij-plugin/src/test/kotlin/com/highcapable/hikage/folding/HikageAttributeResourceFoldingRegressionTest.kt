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
package com.highcapable.hikage.folding

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.resources.ResourceResolver
import com.android.resources.ResourceType
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.kavaref.extension.classOf

/**
 * Verifies the concrete Android resource-value boundary used by attribute folding previews.
 */
class HikageAttributeResourceFoldingRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies scalar resources and reference chains resolve to their concrete preview text. */
    fun testConcreteScalarResourcesAreFoldable() {
        val dimension = reference(ResourceType.DIMEN, "spacing")
        val firstString = reference(ResourceType.STRING, "first")
        val secondString = reference(ResourceType.STRING, "second")
        val resolver = resolver(
            dimension to "10dp",
            firstString to "@string/second",
            secondString to "Resolved"
        )

        assertEquals("10dp", resolveConcreteValue(resolver, dimension))
        assertEquals("Resolved", resolveConcreteValue(resolver, firstString))
    }

    /** Verifies IDs, non-scalar resources, and non-literal colors never replace source text. */
    fun testIdAndNonConcreteResourcesRemainUnfolded() {
        val id = reference(ResourceType.ID, "title")
        val drawable = reference(ResourceType.DRAWABLE, "background")
        val namedColor = reference(ResourceType.COLOR, "brand")
        val resolver = resolver(
            id to "42",
            drawable to "res/drawable/background.xml",
            namedColor to "red"
        )

        assertNull(resolveConcreteValue(resolver, id))
        assertNull(resolveConcreteValue(resolver, drawable))
        assertNull(resolveConcreteValue(resolver, namedColor))
    }

    /** Verifies the preview keeps Android Studio's 60-character presentation boundary. */
    fun testPreviewLengthBoundaryMatchesAndroidStudio() {
        val field = classOf<HikageAttributeResourceFoldingBuilder>().getDeclaredField("FOLD_MAX_LENGTH")
        assertTrue(field.trySetAccessible())

        assertEquals(60, field.getInt(null))
    }

    private fun resolveConcreteValue(resolver: ResourceResolver, reference: ResourceReference): String? {
        val method = classOf<HikageAttributeResourceFoldingBuilder>().getDeclaredMethod(
            "resolveConcreteValue",
            classOf<ResourceResolver>(),
            classOf<ResourceReference>()
        )
        assertTrue(method.trySetAccessible())

        return method.invoke(HikageAttributeResourceFoldingBuilder(), resolver, reference) as? String
    }

    private fun reference(type: ResourceType, name: String) =
        ResourceReference(ResourceNamespace.RES_AUTO, type, name)

    private fun resolver(vararg values: Pair<ResourceReference, String>) = ResourceResolver.withValues(
        *values.map { (reference, value) -> ResourceValueImpl(reference, value) }.toTypedArray()
    )
}