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
package com.highcapable.hikage.analysis.layout

import com.highcapable.hikage.folding.HikageLayoutLookupFoldingBuilder
import com.highcapable.hikage.settings.service.SettingsService
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Verifies the completed Layout ID/root resolve, folding, reference, and completion surface.
 */
class HikageLayoutLookupRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies every supported ID lookup shape resolves to the exact component performer. */
    fun testAllIdLookupShapesResolveAndExposePerformerReferences() {
        val file = configureLayoutUsage(
            "LayoutLookupResolution.kt",
            """
            val arrayLookup = layout["title"]
            val getLookup = layout.get("title")
            val typedGetLookup = layout.get<TextView>("title")
            val nullableLookup = layout.getOrNull("title")
            val typedNullableLookup = layout.getOrNull<TextView>("title")
            """.trimIndent()
        )
        val resolver = HikageLayoutResolver.from(project)
        val strings = PsiTreeUtil.collectElementsOfType(file, classOf<KtStringTemplateExpression>())
            .filter { expression -> expression.text == "\"title\"" }
            .filter { expression -> resolver.resolveIdLookup(expression) != null }

        assertEquals(5, strings.size)
        strings.forEach { expression ->
            val lookup = resolver.resolveIdLookup(expression)
            assertNotNull("Expected ${expression.parent.text} to resolve.", lookup)
            lookup ?: return@forEach
            assertEquals("title", lookup.layoutId.name)
            assertEquals("TextView", lookup.layoutId.performer.text)
            assertEquals("android.widget.TextView", lookup.layoutId.viewClass?.qualifiedName)
            assertTrue(expression.references.any { reference -> reference.resolve()?.text == "TextView" })
        }
    }

    /** Verifies folding excludes source dots and keeps missing or incorrectly typed lookups visible. */
    fun testLookupFoldingUsesSelectorOnlyAndPreservesInspectionTargets() {
        SettingsService.getInstance(project).isLayoutLookupPreviewEnabled = true
        val file = configureLayoutUsage(
            "LayoutLookupFolding.kt",
            """
            val arrayLookup = layout["title"]
            val getLookup = layout.get("title")
            val typedGetLookup = layout.get<TextView>("title")
            val nullableLookup = layout.getOrNull("title")
            val typedNullableLookup = layout.getOrNull<TextView>("title")
            val rootLookup: View = layout.root()
            val typedRootLookup = layout.root<LinearLayout>()
            val incorrectIdLookup = layout.get<LinearLayout>("title")
            val incorrectRootLookup = layout.root<TextView>()
            val missingLookup = layout["missing"]
            """.trimIndent()
        )
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        val descriptors = computeInBackgroundReadAction {
            HikageLayoutLookupFoldingBuilder().buildFoldRegions(file, document, false)
        }
        val folds = descriptors.associate { descriptor ->
            document.text(descriptor.range.startOffset, descriptor.range.endOffset) to descriptor.placeholderText
        }

        assertEquals("Unexpected folds: $folds", 7, descriptors.size)
        assertEquals(".title", folds["[\"title\"]"])
        assertEquals("title", folds["get(\"title\")"])
        assertEquals("title", folds["get<TextView>(\"title\")"])
        assertEquals("title", folds["getOrNull(\"title\")"])
        assertEquals("title", folds["getOrNull<TextView>(\"title\")"])
        assertEquals("root", folds["root()"])
        assertEquals("root", folds["root<LinearLayout>()"])
        assertFalse(folds.containsKey("get<LinearLayout>(\"title\")"))
        assertFalse(folds.containsKey("root<TextView>()"))
        assertFalse(folds.containsKey("[\"missing\"]"))
    }

    /** Verifies ID-string completion rewrites array access through the real typed insert handler. */
    fun testIdStringCompletionCreatesTypedGetLookup() {
        val settings = SettingsService.getInstance(project)
        val wasPreviewEnabled = settings.isLayoutLookupPreviewEnabled
        settings.isLayoutLookupPreviewEnabled = false
        try {
            configureLayoutUsage(
                "LayoutLookupCompletion.kt",
                """
                fun verify() {
                    layout["ti<caret>"]
                }
                """.trimIndent()
            )

            myFixture.completeBasic()?.first { candidate -> candidate.lookupString == "title" }
                ?.let { item -> selectLookupElement(item) }

            assertContains(myFixture.file.text, "layout.get<TextView>(\"title\")")
        } finally {
            settings.isLayoutLookupPreviewEnabled = wasPreviewEnabled
        }
    }

    private fun configureLayoutUsage(fileName: String, usage: String): KtFile {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        return configureKotlinByText(
            fileName,
            """
            package sample

            import android.view.View
            import android.widget.LinearLayout
            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.LinearLayout(
                id: String = "",
                performer: Hikage.Performer.() -> Unit = {}
            ): LinearLayout = error("Test stub")

            @Hikagable
            fun Hikage.Performer.TextView(
                id: String = "",
                performer: Hikage.Performer.() -> Unit = {}
            ): TextView = error("Test stub")

            val layout = Hikagable {
                LinearLayout {
                    TextView(id = "title")
                }
            }

            $usage
            """.trimIndent()
        )
    }

    private fun Document.text(startOffset: Int, endOffset: Int) =
        getText(TextRange(startOffset, endOffset))
}