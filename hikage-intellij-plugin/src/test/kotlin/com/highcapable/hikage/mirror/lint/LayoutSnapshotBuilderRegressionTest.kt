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
package com.highcapable.hikage.mirror.lint

import com.highcapable.hikage.mirror.lint.builder.LayoutSnapshotBuilder
import com.highcapable.hikage.mirror.lint.model.LintIssue
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies Kotlin performer reconstruction and the mirrored upstream issue inventory.
 */
class LayoutSnapshotBuilderRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies performer nesting and static runtime attributes become one XML-equivalent tree. */
    fun testNestedPerformerCallsBuildOneTypedLayoutTree() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "AndroidLintLayoutSnapshot.kt",
            """
            package sample

            import android.widget.LinearLayout
            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.AttributeScope
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.LinearLayout(
                attrs: AttributeScope.() -> Unit = {},
                performer: Hikage.Performer.() -> Unit = {}
            ): LinearLayout = error("Test stub")

            @Hikagable
            fun Hikage.Performer.TextView(
                attrs: AttributeScope.() -> Unit = {},
                performer: Hikage.Performer.() -> Unit = {}
            ): TextView = error("Test stub")

            val layout = Hikagable {
                LinearLayout(
                    attrs = {
                        set("android:contentDescription", "Container")
                    }
                ) {
                    TextView(
                        attrs = {
                            set("android:text", "Hello")
                        }
                    )
                }
            }
            """.trimIndent()
        )
        val snapshot = computeInBackgroundReadAction {
            LayoutSnapshotBuilder(
                file,
                setOf("LinearLayout", "TextView"),
                setOf("contentDescription", "text"),
                visitsAllAttributes = false,
                isAttributeRuntimeEnabled = true
            ).build()
        }
        val root = snapshot.roots.single()
        val child = root.children.single()

        assertEquals("LinearLayout", root.tagName)
        assertEquals("android.widget.LinearLayout", root.viewClass.qualifiedName)
        assertEquals("Container", root.attributes.single().value)
        assertTrue(root.isAttributeModelComplete)
        assertEquals("TextView", child.tagName)
        assertEquals("android.widget.TextView", child.viewClass.qualifiedName)
        assertEquals("Hello", child.attributes.single().value)
        assertTrue(child.isAttributeModelComplete)
    }

    /** Verifies the mirror keeps the exact supported upstream issue IDs unique. */
    fun testMirroredIssueInventoryMatchesTheRegisteredCapabilitySet() {
        val ids = LintIssue.entries.map(LintIssue::id)

        assertEquals(23, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertEquals("ContentDescription", ids.first())
        assertEquals("SmallSp", ids.last())
    }
}