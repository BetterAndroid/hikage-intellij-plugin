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
 * This file is created by fankes on 2026/8/10.
 */
package com.highcapable.hikage.convert

import com.highcapable.hikage.convert.generator.HikagablePropertyRenderer
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinLayoutCall
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.KotlinLayoutParams
import com.highcapable.hikage.convert.output.KotlinSnippetClipboardOutput
import com.highcapable.hikage.convert.output.KotlinSnippetClipboardOutput.OutputKind
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil

/**
 * Verifies the Hikagable Property XML layout conversion output.
 */
class HikagablePropertyConversionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies same-basename naming, wrapper imports, argument style, and Kotlin syntax. */
    fun testPropertyRendererUsesUpperCamelSameBasenameAndDeterministicStyle() {
        installHikageTestApi()
        val snippet = HikagablePropertyRenderer.render(
            root = KotlinLayoutNode(
                viewClassName = "android.view.View",
                call = KotlinLayoutCall(
                    functionName = "View",
                    importName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
                    hasChildPerformerParameter = false
                ),
                layoutParams = KotlinLayoutParams(
                    width = KotlinLayoutParams.Size.MatchParent,
                    height = KotlinLayoutParams.Size.WrapContent
                ),
                id = "content",
                attributes = emptyList(),
                initializers = emptyList(),
                todoAttributes = emptyList(),
                todoComments = emptyList(),
                children = emptyList()
            ),
            layoutResourceName = "account_profile",
            explicitRootLayoutParams = null
        )

        assertEquals(
            """
            val AccountProfile = Hikagable {
                View(
                    id = "content",
                    lparams = LayoutParams(widthMatchParent = true)
                )
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                HikageSymbols.HIKAGABLE_FUNCTION,
                HikageSymbols.HIKAGE_LAYOUT_PARAMS,
                HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION
            ),
            snippet.imports
        )
        assertFalse(snippet.code.contains("AccountProfileLayout"))
        assertFalse(snippet.code.contains(",\n    )"))
        assertTrue(snippet.code.indexOf("id =") < snippet.code.indexOf("lparams ="))
        assertNoPsiErrors(configureKotlinByText(
            "GeneratedProperty.kt",
            snippet.imports.joinToString("\n") { importName -> "import $importName" } + "\n\n" + snippet.code
        ))
    }

    /** Verifies Property paste restores exact imports and qualifies an `R` not owned by the paste target. */
    fun testPropertyPasteRestoresExactImportsAndQualifiesNonTargetResourceClass() {
        installHikageTestApi()
        addProjectFile(
            "com/highcapable/hikage/property/fixture/R.kt",
            """
            package com.highcapable.hikage.property.fixture

            object R {
                object string {
                    const val title = 1
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/layout/View.kt",
            """
            package com.highcapable.hikage.core.layout

            import android.view.View
            import com.highcapable.hikage.core.Hikage

            fun Hikage.Performer.View(init: View.() -> Unit = {}) = Unit
            """.trimIndent()
        )
        val target = configureKotlinByText(
            "PropertyPasteTarget.kt",
            """
            package com.highcapable.hikage.property.fixture.output

            <caret>
            """.trimIndent()
        )
        val snippet = HikagablePropertyRenderer.render(
            root = KotlinLayoutNode(
                viewClassName = "android.view.View",
                call = KotlinLayoutCall(
                    functionName = "View",
                    importName = HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
                    hasChildPerformerParameter = false
                ),
                layoutParams = null,
                id = null,
                attributes = emptyList(),
                initializers = listOf(KotlinLayoutInitializer(
                    memberName = "tag",
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.Resource(
                            resourceClassName = "com.highcapable.hikage.property.fixture.R",
                            resourceType = "string",
                            resourceName = "title",
                            helperName = null
                        )
                    ))
                )),
                todoAttributes = emptyList(),
                todoComments = emptyList(),
                children = emptyList()
            ),
            layoutResourceName = "screen_title",
            explicitRootLayoutParams = null
        )
        assertEquals("com.highcapable.hikage.property.fixture.R", snippet.unqualifiedResourceClassName)
        val settings = CodeInsightSettings.getInstance()
        val previousSetting = settings.ADD_IMPORTS_ON_PASTE
        try {
            settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(snippet),
                OutputKind.HIKAGABLE_PROPERTY
            )

            myFixture.performEditorAction(IdeActions.ACTION_PASTE)
            PlatformTestUtil.waitWithEventsDispatching(
                "The Hikagable Property paste did not restore its exact imports.",
                {
                    target.importDirectives.mapNotNullTo(mutableSetOf()) { directive ->
                        directive.importedFqName?.asString()
                    }.containsAll(setOf(
                        HikageSymbols.HIKAGABLE_FUNCTION,
                        HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION
                    )) && target.text.contains("com.highcapable.hikage.property.fixture.R.string.title")
                },
                10
            )
        } finally {
            settings.ADD_IMPORTS_ON_PASTE = previousSetting
        }

        assertContains(target.text, "val ScreenTitle = Hikagable {")
        assertContains(target.text, "tag = com.highcapable.hikage.property.fixture.R.string.title")
        assertEquals(0, target.importDirectives.count { directive ->
            directive.importedFqName?.asString()?.endsWith(".R") == true
        })
        assertTrue(target.importDirectives.none { directive -> directive.aliasName != null })
        assertTrue(target.importDirectives.none { directive -> directive.isAllUnder })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNoPsiErrors(target)
    }
}