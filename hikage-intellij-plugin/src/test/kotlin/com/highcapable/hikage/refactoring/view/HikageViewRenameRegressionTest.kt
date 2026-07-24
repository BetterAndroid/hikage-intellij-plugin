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
package com.highcapable.hikage.refactoring.view

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Verifies project View discovery, alias detection, and class/file Rename behavior.
 */
class HikageViewRenameRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies that renaming a top-level Hikage View updates references and its same-named file. */
    fun testProjectViewRenameUpdatesClassReferencesAndFileName() {
        installHikageTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "SampleView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView
            class Sample<caret>View(context: Context, attrs: AttributeSet?) : View(context, attrs)

            val viewClass = SampleView::class
            """.trimIndent()
        )
        val view = file.declarations.filterIsInstance<KtClassOrObject>().single()

        assertSame(view, HikageViewRenameTargetResolver.findProjectView(requireNotNull(view.nameIdentifier)))

        myFixture.renameElementAtCaret("RenamedView")

        assertEquals("RenamedView.kt", myFixture.file.name)
        assertContains(myFixture.file.text, "class RenamedView")
        assertContains(myFixture.file.text, "RenamedView::class")
        assertFalse(myFixture.file.text.contains("SampleView"))
    }

    /** Verifies named and positional explicit aliases remain distinguishable from unaliased Views. */
    fun testExplicitAliasResolutionMatchesAnnotationParameterLayout() {
        installHikageTestApi()
        val file = configureKotlinByText(
            "AliasedViews.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView(alias = "NamedAlias")
            class NamedView(context: Context, attrs: AttributeSet?) : View(context, attrs)

            @HikageView(Any::class, "PositionalAlias")
            class PositionalView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val classes = file.declarations.filterIsInstance<KtClassOrObject>().associateBy(KtClassOrObject::getName)

        assertEquals("NamedAlias", HikageViewRenameTargetResolver.findExplicitAlias(classes.getValue("NamedView")))
        assertEquals("PositionalAlias", HikageViewRenameTargetResolver.findExplicitAlias(classes.getValue("PositionalView")))
    }
}