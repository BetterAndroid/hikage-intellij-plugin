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

import com.highcapable.hikage.analysis.layout.helper.HikageLayoutTypeHelper
import com.highcapable.hikage.completion.HikageLayoutIdCompletionConfidence
import com.highcapable.hikage.folding.HikageLayoutLookupFoldingBuilder
import com.highcapable.hikage.inspection.HikageLayoutInspection
import com.highcapable.hikage.settings.service.SettingsService
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import org.jetbrains.kotlin.analysis.api.permissions.forbidAnalysis
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Verifies the completed Layout ID/root resolve, folding, reference, and completion surface.
 */
class HikageLayoutLookupRegressionTest : HikageCodeInsightTestCase() {

    private companion object {
        val EXPECTED_FACTORY_LOOKUP_IDS = setOf("primary_button", "secondary_button")
    }

    /** Verifies per-character Layout ID auto-popup availability never starts Kotlin Analysis. */
    fun testLayoutIdStringAutopopupAvailabilityIsAnalysisFree() {
        val file = configureLayoutUsage(
            "LayoutLookupAutopopup.kt",
            "val lookup = layout[\"ti<caret>tle\"]"
        )
        val offset = myFixture.editor.caretModel.offset
        val contextElement = requireNotNull(file.findElementAt(offset - 1))

        val result = forbidAnalysis("Hikage Layout ID auto-popup availability") {
            HikageLayoutIdCompletionConfidence().shouldSkipAutopopup(
                myFixture.editor,
                contextElement,
                file,
                offset
            )
        }

        assertEquals(ThreeState.NO, result)
    }

    /** Verifies ordinary map and object lookups do not enable Layout ID auto-popup. */
    fun testOrdinaryLookupAutopopupAvailabilityIsAnalysisFree() {
        val file = configureLayoutUsage(
            "OrdinaryLookupAutopopup.kt",
            """
            class Holder {
                fun get(id: String) = id
            }

            val values = mapOf("title" to 1)
            val holder = Holder()
            val mapLookup = values["ti<caret>tle"]
            val objectLookup = holder.get("title")
            """.trimIndent()
        )
        val offset = myFixture.editor.caretModel.offset
        val contextElement = requireNotNull(file.findElementAt(offset - 1))

        val result = forbidAnalysis("ordinary layout lookup auto-popup availability") {
            HikageLayoutIdCompletionConfidence().shouldSkipAutopopup(
                myFixture.editor,
                contextElement,
                file,
                offset
            )
        }

        assertEquals(ThreeState.UNSURE, result)
    }

    /** Verifies nullable `getOrNull` lookups retain the same lightweight Layout ID evidence. */
    fun testNullableLayoutIdStringAutopopupAvailabilityIsAnalysisFree() {
        val file = configureLayoutUsage(
            "NullableLayoutLookupAutopopup.kt",
            "val lookup = layout.getOrNull(\"ti<caret>tle\")"
        )
        val offset = myFixture.editor.caretModel.offset
        val contextElement = requireNotNull(file.findElementAt(offset - 1))

        val result = forbidAnalysis("nullable layout lookup auto-popup availability") {
            HikageLayoutIdCompletionConfidence().shouldSkipAutopopup(
                myFixture.editor,
                contextElement,
                file,
                offset
            )
        }

        assertEquals(ThreeState.NO, result)
    }

    /** Verifies an inferred `hikage` callback parameter remains a valid lightweight receiver. */
    fun testInferredHikageCallbackLayoutLookupAutopopupAvailabilityIsAnalysisFree() {
        addProjectFile(
            "com/highcapable/hikage/extension/sample/Callbacks.kt",
            """
            package com.highcapable.hikage.extension.sample

            import com.highcapable.hikage.core.Hikage

            fun onBind(block: (Hikage, Int) -> Unit) = Unit
            """.trimIndent()
        )
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "InferredCallbackLayoutLookup.kt",
            """
            package sample

            import android.widget.LinearLayout
            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.extension.sample.onBind

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

            fun verify() {
                onBind { hikage, _ ->
                    hikage.getOrNull("ti<caret>tle")
                }
            }
            """.trimIndent()
        )
        val offset = myFixture.editor.caretModel.offset
        val contextElement = requireNotNull(file.findElementAt(offset - 1))

        val result = forbidAnalysis("inferred Hikage callback auto-popup availability") {
            HikageLayoutIdCompletionConfidence().shouldSkipAutopopup(
                myFixture.editor,
                contextElement,
                file,
                offset
            )
        }

        assertEquals(ThreeState.NO, result)
    }

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

    /** Verifies every expression with a concrete Builder type retains its IDs through `build().create(...)`. */
    fun testConcreteBuilderTypeBuildCreateResolvesLayoutIdReference() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "ConcreteBuilderLayoutLookup.kt",
            """
            package com.highcapable.hikage.fixture

            import android.content.Context
            import android.widget.LinearLayout
            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.core.builder.HikageBuilder

            @Hikagable
            fun Hikage.Performer.LinearLayout(
                performer: Hikage.Performer.() -> Unit = {}
            ): LinearLayout = error("Test stub")

            @Hikagable
            fun Hikage.Performer.TextView(
                id: String = ""
            ): TextView = error("Test stub")

            class ConcreteLayout(valueProvider: () -> String) : HikageBuilder {
                private val value by lazy(valueProvider)

                override fun build() = Hikagable(Unit) {
                    LinearLayout {
                        TextView(id = "repository_input")
                    }
                }
            }

            fun createLayout(valueProvider: () -> String) = ConcreteLayout(valueProvider)

            open class InheritedLayoutBase : HikageBuilder {
                override fun build() = Hikagable(Unit) {
                    LinearLayout {
                        TextView(id = "repository_input")
                    }
                }
            }

            class InheritedLayout : InheritedLayoutBase()

            class LayoutOwner(context: Context) {
                private val constructedContent = ConcreteLayout { "value" }.build().create(context)
                private val factoryContent = createLayout { "value" }.build().create(context)
                private val inheritedContent = InheritedLayout().build().create(context)
                private val constructedInput = constructedContent.get<TextView>("repository_input")
                private val factoryInput = factoryContent.get<TextView>("repository_input")
                private val inheritedInput = inheritedContent.get<TextView>("repository_input")
            }
            """.trimIndent()
        )
        val typeHelper = HikageLayoutTypeHelper(project)
        val builderReceivers = PsiTreeUtil.collectElementsOfType(file, classOf<KtCallExpression>())
            .filter { call -> call.calleeExpression?.text == "build" }
            .mapNotNull { call -> (call.parent as? KtQualifiedExpression)?.receiverExpression }
            .filterNot { receiver -> receiver.text == "Hikage" }
        assertEquals(
            listOf("ConcreteLayout", "ConcreteLayout", "InheritedLayout"),
            computeInBackgroundReadAction {
                builderReceivers.map { receiver -> typeHelper.resolveBuilderDeclaration(receiver)?.name }
            }
        )
        val resolver = HikageLayoutResolver.from(project)
        val expressions = PsiTreeUtil.collectElementsOfType(file, classOf<KtCallExpression>())
            .filter { call -> call.calleeExpression?.text == "get" }
            .map { call -> call.valueArguments.single().getArgumentExpression() as KtStringTemplateExpression }

        assertEquals(3, expressions.size)
        expressions.forEach { expression ->
            val lookup = resolver.resolveIdLookup(expression)
            assertNotNull("Expected the concrete Builder type to retain its layout source.", lookup)
            assertEquals("TextView", lookup?.layoutId?.performer?.text)
            val reference = file.findReferenceAt(expression.textOffset + 1)
            assertNotNull("Expected the platform to select the concrete Builder ID reference.", reference)
            assertEquals("TextView", reference?.resolve()?.text)
        }
    }

    /** Verifies an anonymous Builder value exposes its IDs through receiver-dot completion. */
    fun testAnonymousBuilderBuildCreateCompletesLayoutIds() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        configureKotlinByText(
            "AnonymousBuilderLayoutLookup.kt",
            """
            package com.highcapable.hikage.fixture

            import android.content.Context
            import android.widget.LinearLayout
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.core.builder.HikageBuilder

            @Hikagable
            fun Hikage.Performer.LinearLayout(
                id: String = ""
            ): LinearLayout = error("Test stub")

            fun verify(context: Context) {
                val builder = object : HikageBuilder {
                    override fun build() = Hikagable(Unit) {
                        LinearLayout(id = "anonymous_root")
                    }
                }
                val layout = builder.build().create(context)
                layout.<caret>
            }
            """.trimIndent()
        )

        val item = myFixture.completeBasic()?.firstOrNull { candidate -> candidate.lookupString == "anonymous_root" }
        assertNotNull("Expected receiver-dot completion to expose the anonymous Builder ID.", item)
        item ?: return
        selectLookupElement(item)

        assertContains(myFixture.file.text, "layout.get<LinearLayout>(\"anonymous_root\")")
        val file = myFixture.file as KtFile
        val lookupCall = PsiTreeUtil.collectElementsOfType(file, classOf<KtCallExpression>())
            .single { call -> call.calleeExpression?.text == "get" }
        val expression = lookupCall.valueArguments.single().getArgumentExpression() as KtStringTemplateExpression
        val lookup = HikageLayoutResolver.from(project).resolveIdLookup(expression)
        assertNotNull("Expected the completed anonymous Builder lookup to retain its layout source.", lookup)
        val reference = file.findReferenceAt(expression.textOffset + 1)
        assertNotNull("Expected the platform to select the anonymous Builder ID reference.", reference)
        assertEquals("LinearLayout", reference?.resolve()?.text)
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

    /** Verifies an invoked reusable Delegate retains its layout source after assignment to a local Hikage value. */
    fun testInvokedDelegateLocalValueCompletesLayoutIds() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        configureKotlinByText(
            "InvokedDelegateLayoutLookup.kt",
            """
            package com.highcapable.hikage.fixture

            import android.widget.LinearLayout
            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.layout.invoke

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

            val ReusableLayout = Hikage.build {
                LinearLayout {
                    TextView(id = "title")
                }
            }

            fun Hikage.Performer.verify() {
                val layout = ReusableLayout()
                layout.ti<caret>
            }
            """.trimIndent()
        )

        myFixture.completeBasic()?.first { candidate -> candidate.lookupString == "title" }
            ?.let { item -> selectLookupElement(item) }

        assertContains(myFixture.file.text, "layout.get<TextView>(\"title\")")
    }

    /** Verifies a local View factory forwards its static ID arguments into the resolved layout. */
    fun testLocalViewFactoryForwardsStaticLayoutIds() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        addProjectFile(
            "android/widget/ImageButton.kt",
            """
            package android.widget

            open class ImageButton : TextView()
            """.trimIndent()
        )
        myFixture.enableInspections(HikageLayoutInspection.MissingHikageLayoutId())
        val file = configureKotlinByText(
            "LocalViewFactoryLayoutLookup.kt",
            """
            package com.highcapable.hikage.fixture

            import android.widget.ImageButton
            import android.widget.LinearLayout
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.LinearLayout(
                id: String = "",
                performer: Hikage.Performer.() -> Unit = {}
            ): LinearLayout = error("Test stub")

            @Hikagable
            fun Hikage.Performer.ImageButton(
                id: String = ""
            ): ImageButton = error("Test stub")

            val layout = Hikagable {
                LinearLayout {
                    fun createButton(id: String) = ImageButton(id = id)

                    createButton("primary_button")
                    createButton(id = "secondary_button")
                }
            }

            val primary = layout.get<ImageButton>("primary_button")
            val secondary = layout.get<ImageButton>("secondary_button")
            """.trimIndent()
        )
        val resolver = HikageLayoutResolver.from(project)
        val lookups = PsiTreeUtil.collectElementsOfType(file, classOf<KtStringTemplateExpression>())
            .filter { expression -> expression.text.removeSurrounding("\"") in EXPECTED_FACTORY_LOOKUP_IDS }
            .mapNotNull(resolver::resolveIdLookup)

        assertEquals(EXPECTED_FACTORY_LOOKUP_IDS.size, lookups.size)
        assertEquals(
            EXPECTED_FACTORY_LOOKUP_IDS,
            lookups.map { lookup -> lookup.layoutId.name }.toSet()
        )
        assertTrue(lookups.all { lookup -> lookup.layoutId.performer.text == "ImageButton" })
        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        assertFalse(descriptions.any { description -> description.startsWith("Cannot resolve ID") })
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