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
package com.highcapable.hikage.dsl.builder

import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerSpec
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase

/**
 * Verifies the current nested ViewGroup performer source contract in a Kotlin PSI fixture.
 */
class PerformerSourceBuilderRegressionTest : HikageCodeInsightTestCase() {

    fun testNestedViewGroupSourceMatchesTheKspShape() {
        val view = requireNotNull(
            ViewDeclaration.from(
                viewClass = "sample.widgets.Container.NestedView",
                alias = null,
                isViewGroup = true
            )
        )
        val source = PerformerSourceBuilder.createSource(
            PerformerDeclaration(
                spec = PerformerSpec(
                    lparams = "sample.layout.Container.LayoutParams",
                    attrs = true,
                    init = true,
                    performer = true
                ),
                declaration = view,
                source = PerformerDeclaration.Source.ANNOTATION
            )
        )

        assertContains(source, PerformerSourceBuilder.FILE_MARKER)
        assertContains(source, "package com.highcapable.hikage.widget.sample.widgets")
        assertContains(source, "import sample.widgets.Container")
        assertContains(source, "import sample.layout.Container.LayoutParams as Container_LayoutParams")
        assertContains(source, ".Container_NestedView(")
        assertContains(source, "): Container.NestedView = _ViewGroup(")
        assertNoPsiErrors(configureKotlinByText("Container_NestedView.kt", source))
    }

    /** Verifies the minimal non-ViewGroup source shape when optional lambdas are disabled. */
    fun testSimpleViewSourceOmitsDisabledOptionalParameters() {
        val view = requireNotNull(ViewDeclaration.from("sample.widgets.SimpleView", null, false))
        val source = PerformerSourceBuilder.createSource(
            PerformerDeclaration(
                spec = PerformerSpec(
                    lparams = null,
                    attrs = false,
                    init = false,
                    performer = false
                ),
                declaration = view,
                source = PerformerDeclaration.Source.STRICT_FILE
            )
        )

        assertContains(source, "import com.highcapable.hikage.core.layout.View as _View")
        assertContains(source, "fun <reified LP : ViewGroup_LayoutParams> Hikage.Performer<LP>.SimpleView(")
        assertContains(source, "lparams: LayoutParams? = null")
        assertContains(source, "id: String? = null")
        assertFalse(source.contains("attrs: HikageAttribute"))
        assertFalse(source.contains("init: HikageView"))
        assertFalse(source.contains("performer: HikagePerformer"))
        assertContains(source, "= _View(")
        assertNoPsiErrors(configureKotlinByText("SimpleView.kt", source))
    }

    /** Verifies explicit aliases remain the generated function and JVM file identity. */
    fun testExplicitAliasControlsFunctionAndJvmNames() {
        val view = requireNotNull(ViewDeclaration.from("sample.widgets.OriginalView", "StableWidget", false))
        val source = PerformerSourceBuilder.createSource(
            PerformerDeclaration(
                spec = PerformerSpec(null, attrs = true, init = true, performer = false),
                declaration = view,
                source = PerformerDeclaration.Source.OPTIONAL_FILE
            )
        )

        assertEquals("StableWidget", view.functionName)
        assertEquals("com.highcapable.hikage.widget.sample.widgets.StableWidget", view.generatedKey)
        assertContains(source, "@file:JvmName(\"StableWidgetPerformer\")")
        assertContains(source, ".StableWidget(")
        assertNoPsiErrors(configureKotlinByText("StableWidget.kt", source))
    }
}