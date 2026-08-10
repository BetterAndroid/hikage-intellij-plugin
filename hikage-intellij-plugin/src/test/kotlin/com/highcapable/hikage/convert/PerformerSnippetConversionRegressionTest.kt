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
 * This file is created by fankes on 2026/7/29.
 */
package com.highcapable.hikage.convert

import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.ScopeType
import com.android.tools.idea.projectsystem.SourceProviders
import com.highcapable.hikage.convert.generator.PerformerSnippetRenderer
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinLayoutCall
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.ViewConversionOption
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.convert.output.KotlinSnippetClipboardOutput
import com.highcapable.hikage.convert.output.KotlinSnippetPasteProcessor
import com.highcapable.hikage.convert.parser.XmlLayoutParser
import com.highcapable.hikage.convert.resolver.XmlLayoutModelResolver
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerSpec
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.facet.FacetManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.k2.codeinsight.copyPaste.KotlinReferenceTransferableData
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Verifies neutral XML parsing, conservative planning, and Performer snippet rendering.
 */
class PerformerSnippetConversionRegressionTest : HikageCodeInsightTestCase() {

    private companion object {
        val ORDINARY_LAYOUT = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello &quot;Hikage&quot;"
                    android:id="@+id/title"/>
            </LinearLayout>
        """.trimIndent()
    }

    /** Verifies hierarchy, namespaces, raw values, source ranges, and Data Binding wrapper state survive parsing. */
    fun testNeutralParserPreservesXmlSemantics() {
        val file = myFixture.configureByText(
            "activity_main.xml",
            """
            <layout xmlns:android="http://schemas.android.com/apk/res/android">
                <data>
                    <variable name="title" type="String"/>
                </data>
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">
                    <TextView
                        android:id="@+id/title"
                        android:text="Hello &quot;Hikage&quot;"/>
                </LinearLayout>
            </layout>
            """.trimIndent()
        ) as XmlFile

        val outcome = XmlLayoutParser.parse(file)
        val layout = requireNotNull(outcome.value)
        val root = layout.root
        val child = root.children.single()
        val text = child.attributes.single { attribute -> attribute.localName == "text" }

        assertTrue(layout.hasDataBindingWrapper)
        assertEquals(1, layout.dataBindingDeclarationCount)
        assertEquals("LinearLayout", root.rawClassName)
        assertEquals("TextView", child.rawClassName)
        assertEquals("http://schemas.android.com/apk/res/android", text.namespaceUri)
        assertEquals("android", text.namespacePrefix)
        assertEquals("Hello &quot;Hikage&quot;", text.rawValue)
        assertEquals("Hello \"Hikage\"", text.value)
        assertEquals(XmlLayoutAttribute.Kind.ID, child.attributes.first().kind)
        assertTrue(text.source.textRange.length > 0)
        assertEquals(file.virtualFile.url, text.source.fileUrl)
        assertEquals(listOf(ConversionDiagnostic.Kind.DATA_BINDING), outcome.diagnostics.map(ConversionDiagnostic::kind))
    }

    /** Verifies an ordinary tree renders without trailing commas and keeps `id` first regardless of XML order. */
    fun testOrdinaryHierarchyRendersGoldenPerformerSnippet() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(ORDINARY_LAYOUT)
        val parsed = requireNotNull(XmlLayoutParser.parse(layoutFile).value)
        val resolved = XmlLayoutModelResolver.resolve(
            layout = parsed,
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))
        val code = snippet.code

        assertEquals(
            """
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                attrs = {
                    android {
                        set("orientation", "vertical")
                    }
                }
            ) {
                TextView(
                    id = "title",
                    lparams = LayoutParams(),
                    attrs = {
                        android {
                            set("text", "Hello \"Hikage\"")
                        }
                    }
                )
            }
            """.trimIndent(),
            code
        )
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.attribute.android",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.android.widget.LinearLayout",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
        assertFalse(resolved.hasErrors)
        assertNoPsiErrors(configureKotlinByText("Snippet.kt", "fun test() {\n$code\n}"))
    }

    /** Verifies common integer dp and px sizes use the generic parent LayoutParams contract. */
    fun testCommonRootDimensionsUseGenericParentLayoutParams() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="48dp"
                android:layout_height="24px"/>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            TextView(
                lparams = LayoutParams(width = 48.dp, height = 24)
            )
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
        assertFalse(resolved.hasErrors)
    }

    /** Verifies compatible mode keeps common sizes in attrs when the output has no root parent contract. */
    fun testUnknownRootLayoutParamsContractFallsBackToAttrs() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="48dp"/>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = null
        )
        val code = PerformerSnippetRenderer.render(requireNotNull(resolved.value)).code

        assertFalse(code.contains("lparams ="))
        assertContains(code, "set(\"layout_width\", \"match_parent\")")
        assertContains(code, "set(\"layout_height\", \"48dp\")")
    }

    /** Verifies a missing runtime-attribute capability forces both attrs-producing options to their only fallbacks. */
    fun testMissingRuntimeAttributeForcesOnlyModes() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(ORDINARY_LAYOUT)
        val parsed = requireNotNull(XmlLayoutParser.parse(layoutFile).value)
        val resolved = XmlLayoutModelResolver.resolve(
            layout = parsed,
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.FULLY_ATTRIBUTES.effectiveOption(false),
            layoutParamsOption = LayoutParamsConversionOption.COMPATIBLE_MODE.effectiveOption(false)
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))
        val code = snippet.code

        assertFalse("Only modes must not emit an attrs block for unproven attributes.", code.contains("attrs ="))
        assertContains(code, "lparams = LayoutParams(matchParent = true)")
        assertContains(code, "lparams = LayoutParams()")
        assertFalse(code.contains("TODO: Convert android:layout_width"))
        assertContains(code, "TODO: Convert android:orientation")
        assertContains(code, "TODO: Convert android:text")
        assertTrue(snippet.imports.none { importName ->
            importName.startsWith("com.highcapable.hikage.core.attribute.")
        })
        assertEquals(2, resolved.diagnostics.count { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.TODO_ATTRIBUTE
        })
        assertNoPsiErrors(configureKotlinByText("OnlyModesSnippet.kt", "fun test() {\n$code\n}"))
    }

    /** Verifies compatible mode atomically falls back when one layout attribute is unsupported. */
    fun testPreferLayoutParamsFallsBackWholeGroupToAttrs() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_margin="8dp"/>
            </LinearLayout>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        val code = PerformerSnippetRenderer.render(requireNotNull(resolved.value)).code

        assertFalse(code.contains("lparams ="))
        assertContains(code, "set(\"layout_width\", \"match_parent\")")
        assertContains(code, "set(\"layout_height\", \"wrap_content\")")
        assertContains(code, "set(\"layout_margin\", \"8dp\")")
    }

    /** Verifies proven MarginLayoutParams and padding groups use exact BetterAndroid and AndroidX update imports. */
    fun testSpacingUsesBetterAndroidAxisAndAndroidXSideUpdates() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginHorizontal="8dp"
                    android:layout_marginVertical="6dp"
                    android:layout_marginLeft="4dp"
                    android:layout_marginTop="-2px"
                    android:layout_marginRight="5dp"
                    android:layout_marginStart="12dp"
                    android:paddingHorizontal="16dp"
                    android:paddingTop="4dp"
                    android:paddingStart="20dp"
                    android:paddingEnd="24dp"/>
            </LinearLayout>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = listOf(
                performer(
                    "android.widget.LinearLayout",
                    isViewGroup = true,
                    lparams = "android.widget.LinearLayout.LayoutParams"
                ),
                performer("android.widget.TextView", isViewGroup = false)
            ),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            LinearLayout() {
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        updateMargins(horizontal = 8.dp, vertical = 6.dp)
                        updateMargins(left = 4.dp, top = -2, right = 5.dp)
                        updateMarginsRelative(start = 12.dp)
                    }
                ) {
                    updatePaddingRelative(horizontal = 16.dp)
                    updatePaddingRelative(start = 20.dp, top = 4.dp, end = 24.dp)
                }
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "androidx.core.view.updateMargins",
                "androidx.core.view.updateMarginsRelative",
                "androidx.core.view.updatePaddingRelative",
                "com.highcapable.betterandroid.ui.extension.view.updateMargins",
                "com.highcapable.betterandroid.ui.extension.view.updatePaddingRelative",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.android.widget.LinearLayout",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
        assertFalse(resolved.hasErrors)
        assertFalse(snippet.code.contains(",\n                    }"))
    }

    /** Verifies resolved View theme dimensions use BetterAndroid only where View `init` exposes `context`. */
    fun testThemeDimensionViewAttributeUsesBetterAndroidContextExtension() {
        installHikageTestApi()
        addProjectFile(
            "android/view/inspector/InspectableProperty.java",
            """
            package android.view.inspector;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.METHOD, ElementType.FIELD})
            public @interface InspectableProperty {
                String name() default "";
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/ThemeDimensionView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class ThemeDimensionView extends View {
                @InspectableProperty(name = "spacing")
                public float getSpacing() {
                    return 0f;
                }

                public void setSpacing(float spacing) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "app/src/main/res/values/attrs.xml",
            """
            <resources>
                <attr name="spacing" format="dimension"/>
                <attr name="dimensionHikageSpacingPrimary" format="dimension"/>
            </resources>
            """.trimIndent()
        )
        val layoutFile = addLayoutFile(
            """
            <com.highcapable.hikage.fixture.ThemeDimensionView
                xmlns:app="http://schemas.android.com/apk/res-auto"
                app:spacing="?dimensionHikageSpacingPrimary"/>
            """.trimIndent()
        )
        val facet = installAndroidSourceProvider(layoutFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val attributeResolver = requireNotNull(AndroidAttributeResolver.from(layoutFile))
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = facet,
            declarations = listOf(performer(
                "com.highcapable.hikage.fixture.ThemeDimensionView",
                isViewGroup = false
            )),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            attributeResolver = attributeResolver,
            resourcePackageName = "com.highcapable.hikage.fixture"
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            ThemeDimensionView {
                spacing = context.getThemeAttrsDimension(R.attr.dimensionHikageSpacingPrimary)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                "com.highcapable.hikage.fixture.R",
                "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.ThemeDimensionView"
            ),
            snippet.imports
        )
        assertEquals("com.highcapable.hikage.fixture.R", snippet.unqualifiedResourceClassName)
        assertFalse(snippet.code.contains("attrs ="))
        assertFalse(resolved.hasErrors)
    }

    /** Verifies external resources stay qualified while the current module keeps the ordinary `R` import. */
    fun testExternalResourceClassUsesQualifiedReference() {
        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "com.highcapable.hikage.fixture.ThemeDimensionView",
            call = KotlinLayoutCall(
                functionName = "ThemeDimensionView",
                importName = "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.ThemeDimensionView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(
                KotlinLayoutInitializer(
                    memberName = "setImageResource",
                    memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                    arguments = listOf(KotlinLayoutInitializer.Argument(value =
                        KotlinLayoutInitializer.Value.Resource(
                            resourceClassName = "com.highcapable.hikage.fixture.R",
                            resourceType = "drawable",
                            resourceName = "hikageIcon",
                            helperName = null
                        )
                    ))
                ),
                KotlinLayoutInitializer(
                    memberName = "spacing",
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(value =
                        KotlinLayoutInitializer.Value.ThemeAttribute(
                            resourceClassName = "com.highcapable.hikage.fixture.theme.R",
                            resourceName = "dimensionHikageSpacingPrimary",
                            functionName = "getThemeAttrsDimension",
                            importName = "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                            isCurrentModuleResource = false
                        )
                    ))
                )
            ),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))

        assertEquals(
            """
            ThemeDimensionView {
                setImageResource(R.drawable.hikageIcon)
                spacing = context.getThemeAttrsDimension(com.highcapable.hikage.fixture.theme.R.attr.dimensionHikageSpacingPrimary)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                "com.highcapable.hikage.fixture.R",
                "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.ThemeDimensionView"
            ),
            snippet.imports
        )
        assertEquals("com.highcapable.hikage.fixture.R", snippet.unqualifiedResourceClassName)
    }

    /** Verifies a known theme dimension still retains the complete LayoutParams group through attrs. */
    fun testThemeDimensionMarginsKeepAtomicLayoutAttrsFallback() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        addProjectFile(
            "app/src/main/res/values/attrs.xml",
            """
            <resources>
                <attr name="dimensionHikageSpacingPrimary" format="dimension"/>
            </resources>
            """.trimIndent()
        )
        val layoutFile = addLayoutFile(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginLeft="?dimensionHikageSpacingPrimary"
                    android:layout_marginRight="?dimensionHikageSpacingPrimary"/>
            </LinearLayout>
            """.trimIndent()
        )
        val facet = installAndroidSourceProvider(layoutFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val attributeResolver = requireNotNull(AndroidAttributeResolver.from(layoutFile))
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = facet,
            declarations = listOf(
                performer(
                    "android.widget.LinearLayout",
                    isViewGroup = true,
                    lparams = "android.widget.LinearLayout.LayoutParams"
                ),
                performer("android.widget.TextView", isViewGroup = false)
            ),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            attributeResolver = attributeResolver,
            resourcePackageName = "com.highcapable.hikage.fixture"
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            LinearLayout(
                lparams = LayoutParams(matchParent = true)
            ) {
                TextView(
                    attrs = {
                        android {
                            set("layout_width", "match_parent")
                            set("layout_height", "wrap_content")
                            set("layout_marginLeft", "?dimensionHikageSpacingPrimary")
                            set("layout_marginRight", "?dimensionHikageSpacingPrimary")
                        }
                    }
                )
            }
            """.trimIndent(),
            snippet.code
        )
        assertFalse(snippet.code.contains("updateMargins"))
        assertFalse(resolved.hasErrors)
    }

    /** Verifies absolute padding keeps every side override after the BetterAndroid axis baseline. */
    fun testAbsolutePaddingUsesBetterAndroidAxisAndAndroidXSideUpdates() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:padding="8dp"
                android:paddingLeft="4dp"
                android:paddingTop="2px"
                android:paddingRight="5dp"
                android:paddingBottom="6dp"/>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            TextView(
                lparams = LayoutParams()
            ) {
                updatePadding(horizontal = 8.dp, vertical = 8.dp)
                updatePadding(left = 4.dp, top = 2, right = 5.dp, bottom = 6.dp)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "androidx.core.view.updatePadding",
                "com.highcapable.betterandroid.ui.extension.view.updatePadding",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
        assertFalse(resolved.hasErrors)
    }

    /** Verifies mixed absolute and relative horizontal padding keeps the complete group in attrs. */
    fun testMixedAbsoluteAndRelativePaddingFallsBackToAttrs() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:paddingLeft="4dp"
                android:paddingStart="8dp"/>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.COMPATIBLE_MODE
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertContains(snippet.code, "set(\"paddingLeft\", \"4dp\")")
        assertContains(snippet.code, "set(\"paddingStart\", \"8dp\")")
        assertFalse(snippet.code.contains("updatePadding"))
        assertTrue(snippet.imports.none { importName -> importName.contains("updatePadding") })
        assertFalse(resolved.hasErrors)
    }

    /** Verifies the reported sample derives its `R` package and converts framework LayoutParams and `init` writes. */
    fun testPreferLayoutParamsConvertsReportedConstraintLayoutSample() {
        installHikageTestApi()
        addProjectFile(
            "android/graphics/drawable/Drawable.java",
            """
            package android.graphics.drawable;

            public class Drawable {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/TextView.java",
            """
            package android.widget;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class TextView extends View {
                @InspectableProperty
                public CharSequence getText() {
                    return null;
                }

                public void setText(CharSequence text) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/ImageView.java",
            """
            package android.widget;

            import android.annotation.DrawableRes;
            import android.graphics.drawable.Drawable;
            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class ImageView extends View {
                @InspectableProperty(name = "src")
                public Drawable getDrawable() {
                    return null;
                }

                /** @attr ref android.R.styleable#ImageView_src */
                public void setImageResource(@DrawableRes int resourceId) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/R.java",
            """
            package com.highcapable.hikage.fixture;

            public final class R {
                public static final class drawable {
                    public static final int ic_launcher_background = 1;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "androidx/constraintlayout/widget/ConstraintLayout.kt",
            """
            package androidx.constraintlayout.widget

            import android.view.ViewGroup

            open class ConstraintLayout : ViewGroup() {
                open class LayoutParams : ViewGroup.LayoutParams()
            }
            """.trimIndent()
        )
        val virtualFile = myFixture.copyFileToProject(
            "convert/layoutParams/prefer_layout_params.xml",
            "app/src/main/res/layout/sample.xml"
        )
        val layoutFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as XmlFile
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations() + listOf(
                performer("android.widget.ImageView", isViewGroup = false),
                performer(
                    "androidx.constraintlayout.widget.ConstraintLayout",
                    isViewGroup = true,
                    lparams = "androidx.constraintlayout.widget.ConstraintLayout.LayoutParams"
                )
            ),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            ConstraintLayout(
                lparams = LayoutParams(matchParent = true)
            ) {
                TextView(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    updatePadding(left = 10.dp)
                    text = "hello"
                }
                ImageView(
                    lparams = LayoutParams(matchParent = true)
                    // TODO: Convert app:srcCompat = "@drawable/ic_launcher_background" manually.
                ) {
                    setImageResource(R.drawable.ic_launcher_background)
                }
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "androidx.core.view.updatePadding",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.fixture.R",
                "com.highcapable.hikage.widget.android.widget.ImageView",
                "com.highcapable.hikage.widget.android.widget.TextView",
                "com.highcapable.hikage.widget.androidx.constraintlayout.widget.ConstraintLayout"
            ),
            snippet.imports
        )
        assertFalse(resolved.hasErrors)
        assertNoPsiErrors(configureKotlinByText("InitSnippet.kt", "fun test() {\n${snippet.code}\n}"))
    }

    /** Verifies a representative View section keeps boolean names, symbolic integers, and LinearLayout weight valid. */
    fun testRepresentativeViewSectionConvertsBooleanIntDefAndLayoutWeight() {
        installHikageTestApi()
        installRepresentativeAndroidWidgetApi()
        val virtualFile = myFixture.copyFileToProject(
            "convert/layoutParams/complex_view_section.xml",
            "app/src/main/res/layout/view_section.xml"
        )
        val layoutFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as XmlFile
        val viewClasses = listOf(
            "TextView",
            "Button",
            "ImageView",
            "ProgressBar",
            "CheckBox",
            "EditText",
            "ImageButton",
            "RatingBar",
            "Switch",
            "Space"
        )
        val declarations = listOf(performer(
            "android.widget.LinearLayout",
            isViewGroup = true,
            lparams = "android.widget.LinearLayout.LayoutParams"
        )) + viewClasses.map { name -> performer("android.widget.$name", isViewGroup = false) }
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = declarations,
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))
        val code = snippet.code

        assertContains(code, "orientation = LinearLayout.VERTICAL")
        assertContains(code, "orientation = LinearLayout.HORIZONTAL")
        assertContains(code, "gravity = Gravity.CENTER_VERTICAL")
        assertContains(code, "gravity = Gravity.CENTER")
        assertFalse("Gravity metadata regressed to an integer literal.",
            "(?m)^\\s+gravity = [-+]?\\d+$".toRegex().containsMatchIn(code))
        assertEquals(2, "setImageResource\\(android\\.R\\.drawable\\.[a-z_]+\\)".toRegex()
            .findAll(code).count())
        assertEquals(3, "setTextColor(stateColorResource(R.color.black))".toRegex(RegexOption.LITERAL)
            .findAll(code).count())
        assertContains(code, "setTextColor(stateColorResource(R.color.white))")
        assertEquals(2, "isSingleLine = true".toRegex().findAll(code).count())
        assertEquals(2, "isAllCaps = false".toRegex().findAll(code).count())
        assertEquals(2, "isIndeterminate = false".toRegex().findAll(code).count())
        assertContains(code, "isChecked = false")
        assertEquals(2, "isChecked = true".toRegex().findAll(code).count())
        assertEquals(11, "weight = [12]f".toRegex().findAll(code).count())
        assertFalse(code.contains("set(\"layout_weight\""))
        assertFalse(code.contains("set(\"src\""))
        assertFalse(code.contains("set(\"textColor\""))
        assertFalse("LayoutParams size was duplicated inside its initializer.",
            "LayoutParams(?:\\([^)]*\\))? \\{[^}]*(?:\\bwidth|\\bheight)\\s*="
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(code))
        assertFalse("Invalid lowered Java boolean property remained in the generated snippet.",
            "(?m)^\\s+(indeterminate|checked|singleLine|allCaps) =".toRegex().containsMatchIn(code))
        assertContains(snippet.imports.joinToString(), "android.view.Gravity")
        assertContains(snippet.imports.joinToString(), "android.widget.LinearLayout")
        assertContains(snippet.imports.joinToString(), "com.highcapable.hikage.fixture.R")
        assertFalse(snippet.imports.contains("android.R"))
        assertFalse(resolved.hasErrors)
        assertNoPsiErrors(configureKotlinByText("ViewSectionSnippet.kt", "fun test() {\n$code\n}"))
    }

    /** Verifies single-file snippet conversion refuses to include rather than emitting a partial hierarchy. */
    fun testSingleFileSnippetRejectsInclude() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <include layout="@layout/content"/>
                <include layout="@layout/header"/>
                <include layout="@layout/footer"/>
            </LinearLayout>
            """.trimIndent()
        )
        val parsed = requireNotNull(XmlLayoutParser.parse(layoutFile).value)
        val resolved = XmlLayoutModelResolver.resolve(
            layout = parsed,
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )

        assertNull(resolved.value)
        assertTrue(resolved.hasErrors)
        assertEquals(3, resolved.diagnostics.count { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.UNSUPPORTED_NODE &&
                diagnostic.message.contains("batch include/merge")
        })
        val notification = captureNotification {
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(value = null, diagnostics = resolved.diagnostics)
            )
        }
        assertEquals(
            "Failed to convert selected XML layout.<br>" +
                "&lt;include&gt; node requires batch include/merge conversion, " +
                "cannot be copied as a single file snippet. — 3 occurrences: lines 4, 5, 6",
            notification.content
        )
    }

    /** Verifies repeated notification details keep the total while limiting their line list. */
    fun testNotificationDetailsLimitRepeatedLocationsWithoutDroppingCount() {
        val layoutFile = addLayoutFile(
            """
            <LinearLayout>
                <include layout="@layout/first"/>
                <include layout="@layout/second"/>
                <include layout="@layout/third"/>
                <include layout="@layout/fourth"/>
                <include layout="@layout/fifth"/>
            </LinearLayout>
            """.trimIndent()
        )
        val diagnostics = requireNotNull(XmlLayoutParser.parse(layoutFile).value).root.children.map { child ->
            ConversionDiagnostic(
                severity = ConversionDiagnostic.Severity.ERROR,
                kind = ConversionDiagnostic.Kind.UNSUPPORTED_NODE,
                message = "Repeated <include> diagnostic.",
                source = child.source
            )
        }

        val notification = captureNotification {
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(value = null, diagnostics = diagnostics)
            )
        }

        assertEquals(
            "Failed to convert selected XML layout.<br>" +
                "Repeated &lt;include&gt; diagnostic. — 5 occurrences: lines 2, 3, 4…",
            notification.content
        )
    }

    /** Verifies distinct diagnostic identities and unavailable sources are not merged or dropped. */
    fun testNotificationDetailsKeepDistinctDiagnosticsAndMissingSourceFallback() {
        val layoutFile = addLayoutFile(
            """
            <LinearLayout>
                <include layout="@layout/first"/>
                <include layout="@layout/second"/>
            </LinearLayout>
            """.trimIndent()
        )
        val children = requireNotNull(XmlLayoutParser.parse(layoutFile).value).root.children
        val notification = captureNotification {
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(
                    value = null,
                    diagnostics = listOf(
                        ConversionDiagnostic(
                            severity = ConversionDiagnostic.Severity.ERROR,
                            kind = ConversionDiagnostic.Kind.UNSUPPORTED_NODE,
                            message = "Shared diagnostic.",
                            source = children[0].source
                        ),
                        ConversionDiagnostic(
                            severity = ConversionDiagnostic.Severity.ERROR,
                            kind = ConversionDiagnostic.Kind.UNKNOWN_VIEW,
                            message = "Shared diagnostic.",
                            source = children[1].source
                        ),
                        ConversionDiagnostic(
                            severity = ConversionDiagnostic.Severity.ERROR,
                            kind = ConversionDiagnostic.Kind.ANDROID_MODEL_UNAVAILABLE,
                            message = "Diagnostic without source."
                        )
                    )
                )
            )
        }

        assertEquals(
            "Failed to convert selected XML layout.<br>" +
                "Line 2: Shared diagnostic.<br>" +
                "Line 3: Shared diagnostic.<br>" +
                "Diagnostic without source.",
            notification.content
        )
    }

    /** Verifies grouped diagnostics identify their files when one report spans multiple layouts. */
    fun testNotificationDetailsUseFileNamesAcrossLayouts() {
        val firstLayout = addLayoutFile(
            """
            <LinearLayout>
                <include layout="@layout/content"/>
            </LinearLayout>
            """.trimIndent()
        )
        val secondLayout = addProjectFile(
            "app/src/main/res/layout/content.xml",
            """
            <LinearLayout>
                <include layout="@layout/detail"/>
            </LinearLayout>
            """.trimIndent()
        ) as XmlFile
        val sources = listOf(firstLayout, secondLayout).map { layout ->
            requireNotNull(XmlLayoutParser.parse(layout).value).root.children.single().source
        }
        val notification = captureNotification {
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(
                    value = null,
                    diagnostics = sources.map { source ->
                        ConversionDiagnostic(
                            severity = ConversionDiagnostic.Severity.ERROR,
                            kind = ConversionDiagnostic.Kind.UNSUPPORTED_NODE,
                            message = "Repeated include diagnostic.",
                            source = source
                        )
                    }
                )
            )
        }

        assertEquals(
            "Failed to convert selected XML layout.<br>" +
                "Repeated include diagnostic. — 2 occurrences: activity_main.xml:2, content.xml:2",
            notification.content
        )
    }

    /** Verifies the exact framework View uses Hikage's built-in non-generic call without performer lookup. */
    fun testFrameworkViewUsesBuiltInCallWithoutPerformerFallback() {
        installHikageTestApi()
        val layoutFile = addLayoutFile(
            """
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:id="@+id/divider"/>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = listOf(performer(AndroidSymbols.VIEW_CLASS, isViewGroup = false)),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            duplicateViewClasses = setOf(AndroidSymbols.VIEW_CLASS)
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))

        assertEquals(
            """
            View(
                id = "divider",
                lparams = LayoutParams(widthMatchParent = true)
            )
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.core.layout.View"
            ),
            snippet.imports
        )
        assertTrue(resolved.diagnostics.isEmpty())
        assertNoPsiErrors(configureKotlinByText("FrameworkViewSnippet.kt", "fun test() {\n${snippet.code}\n}"))
    }

    /** Verifies the abstract framework ViewGroup never enters performer or generic fallback resolution. */
    fun testFrameworkViewGroupCannotUseBuiltInOrGenericCall() {
        installHikageTestApi()
        val layoutFile = addLayoutFile("<view class=\"android.view.ViewGroup\"/>")
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = emptyList(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            duplicateViewClasses = setOf(AndroidSymbols.VIEW_GROUP_CLASS)
        )

        assertNull(resolved.value)
        assertEquals(1, resolved.diagnostics.size)
        assertEquals(ConversionDiagnostic.Kind.MISSING_PERFORMER, resolved.diagnostics.single().kind)
        assertEquals(ConversionDiagnostic.Severity.ERROR, resolved.diagnostics.single().severity)
    }

    /** Verifies missing performers directly use proven generic View/ViewGroup calls in a snippet. */
    fun testMissingPerformersUseGenericSnippetFallback() {
        installHikageTestApi()
        installGenericFallbackTestApi()
        val layoutFile = addLayoutFile(
            """
            <sample.FallbackContainer xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:id="@+id/root">
                <sample.FallbackView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello"
                    android:id="@+id/title"/>
            </sample.FallbackContainer>
            """.trimIndent()
        )
        installAndroidSourceProvider(layoutFile)
        enableHikageRuntimeAttribute()
        val resolved = PerformerSnippetConverter.convert(layoutFile)
        val snippet = requireNotNull(resolved.value)

        assertEquals(
            """
            ViewGroup<FallbackContainer, FallbackContainer.LayoutParams>(
                id = "root",
                lparams = LayoutParams(matchParent = true)
            ) {
                View<FallbackView>(
                    id = "title",
                    lparams = LayoutParams(),
                    attrs = {
                        android {
                            set("text", "Hello")
                        }
                    }
                )
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.attribute.android",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.core.layout.View",
                "com.highcapable.hikage.core.layout.ViewGroup",
                "sample.FallbackContainer",
                "sample.FallbackView"
            ),
            snippet.imports
        )
        assertEquals(2, resolved.diagnostics.count { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.GENERIC_VIEW_FALLBACK &&
                diagnostic.severity == ConversionDiagnostic.Severity.WARNING
        })
        assertFalse(resolved.hasErrors)
        assertNoPsiErrors(configureKotlinByText("GenericSnippet.kt", "fun test() {\n${snippet.code}\n}"))

        KotlinSnippetClipboardOutput.publish(project, resolved)
        assertEquals(snippet.code, CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        val importData = requireNotNull(CopyPasteManager.getInstance().contents)
            .getTransferData(KotlinSnippetPasteProcessor.TransferableData.dataFlavor)
            as KotlinSnippetPasteProcessor.TransferableData
        assertEquals(snippet.imports, importData.imports)
        assertTrue(importData.imports.none { importName -> importName.endsWith(".*") })
    }

    /** Verifies generic fallback remains the final failure when construction cannot be proven. */
    fun testMissingPerformerWithUnprovenConstructorStopsOutput() {
        installHikageTestApi()
        addProjectFile(
            "sample/NoCompatibleConstructorView.kt",
            """
            package sample

            import android.view.View

            class NoCompatibleConstructorView : View()
            """.trimIndent()
        )
        val layoutFile = addLayoutFile("<sample.NoCompatibleConstructorView/>")
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = emptyList(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )

        assertNull(resolved.value)
        assertTrue(resolved.diagnostics.any { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.MISSING_PERFORMER &&
                diagnostic.severity == ConversionDiagnostic.Severity.ERROR
        })
    }

    /** Verifies a missing ViewGroup performer fails only when its child LayoutParams contract is unproven. */
    fun testMissingViewGroupPerformerWithUnprovenChildLayoutParamsStopsOutput() {
        installHikageTestApi()
        addProjectFile(
            "sample/UnknownLayoutParamsGroup.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.ViewGroup

            class UnknownLayoutParamsGroup(
                context: Context,
                attrs: AttributeSet?
            ) : ViewGroup(context, attrs)
            """.trimIndent()
        )
        val layoutFile = addLayoutFile("<sample.UnknownLayoutParamsGroup/>")
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = emptyList(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )

        assertNull(resolved.value)
        assertTrue(resolved.diagnostics.any { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.MISSING_PERFORMER &&
                diagnostic.severity == ConversionDiagnostic.Severity.ERROR
        })
    }

    /** Verifies duplicate Hikage string IDs stop output before runtime LayoutSession failure. */
    fun testDuplicateStringIdsStopOutput() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/title"/>
                <TextView android:id="@id/title"/>
            </LinearLayout>
            """.trimIndent()
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = requireNotNull(XmlLayoutParser.parse(layoutFile).value),
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )

        assertNull(resolved.value)
        assertTrue(resolved.diagnostics.any { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.INVALID_ID &&
                diagnostic.severity == ConversionDiagnostic.Severity.ERROR
        })
    }

    /** Verifies `<view class>`, app attrs, tools omission, and style TODOs use one conservative model. */
    fun testViewTagAndSpecialAttributesStayExplicit() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        val layoutFile = addLayoutFile(
            """
            <view xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                xmlns:tools="http://schemas.android.com/tools"
                class="android.widget.TextView"
                style="@style/Headline"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:customFlag="enabled"
                tools:text="Preview"/>
            """.trimIndent()
        )
        val parsed = requireNotNull(XmlLayoutParser.parse(layoutFile).value)
        assertEquals(
            AndroidSymbols.TOOLS_NAMESPACE_URI,
            parsed.root.attributes.single { attribute ->
                attribute.kind == XmlLayoutAttribute.Kind.TOOLS
            }.namespaceUri
        )
        val resolved = XmlLayoutModelResolver.resolve(
            layout = parsed,
            facet = installAndroidSourceProvider(layoutFile),
            declarations = performerDeclarations(),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
        )
        val snippet = PerformerSnippetRenderer.render(requireNotNull(resolved.value))
        val code = snippet.code

        assertContains(code, "TextView(")
        assertContains(code, "app {")
        assertContains(code, "set(\"customFlag\", \"enabled\")")
        assertContains(code, "TODO: Convert style = \"@style/Headline\" manually.")
        assertFalse(code.contains("tools:text"))
        assertFalse(code.contains("class ="))
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.attribute.app",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
        assertTrue(snippet.imports.none { importName -> importName.endsWith(".*") })
        assertEquals(
            setOf(
                ConversionDiagnostic.Kind.TODO_ATTRIBUTE,
                ConversionDiagnostic.Kind.IGNORED_TOOLS_ATTRIBUTE
            ),
            resolved.diagnostics.mapTo(mutableSetOf(), ConversionDiagnostic::kind)
        )
    }

    /** Verifies successful output publishes plain text plus import-only Hikage metadata. */
    fun testClipboardOutputPublishesOnlySuccessfulSnippet() {
        val clipboard = CopyPasteManager.getInstance()
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setContents(StringSelection("existing"))
        KotlinSnippetClipboardOutput.publish(
            project,
            ConversionOutcome(
                value = null,
                diagnostics = listOf(ConversionDiagnostic(
                    severity = ConversionDiagnostic.Severity.ERROR,
                    kind = ConversionDiagnostic.Kind.INVALID_ROOT,
                    message = "Invalid test layout"
                ))
            )
        )
        assertEquals("existing", clipboard.getContents(DataFlavor.stringFlavor))

        KotlinSnippetClipboardOutput.publish(
            project,
            ConversionOutcome(KotlinSnippet(
                code = "TextView(attrs = { android { set(\"text\", \"Hello\") } })",
                imports = listOf(
                    "com.highcapable.hikage.core.attribute.android",
                    "com.highcapable.hikage.widget.android.widget.TextView"
                )
            ))
        )
        val snippet = "TextView(attrs = { android { set(\"text\", \"Hello\") } })"
        assertEquals(snippet, clipboard.getContents(DataFlavor.stringFlavor))
        val contents = requireNotNull(clipboard.contents)
        assertTrue(contents.isDataFlavorSupported(KotlinSnippetPasteProcessor.TransferableData.dataFlavor))
        assertFalse(contents.isDataFlavorSupported(KotlinReferenceTransferableData.dataFlavor))
        val importData = contents.getTransferData(KotlinSnippetPasteProcessor.TransferableData.dataFlavor)
            as KotlinSnippetPasteProcessor.TransferableData
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.attribute.android",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            importData.imports
        )
        assertNull(importData.unqualifiedResourceClassName)
        assertTrue(importData.imports.none { importName -> importName.endsWith(".*") })
    }

    /** Verifies Kotlin paste restores the exact Performer and attrs imports without a wildcard. */
    fun testClipboardSnippetAddsUsedImportsOnKotlinPaste() {
        addProjectFile(
            "android/view/ViewGroup.kt",
            """
            package android.view

            open class ViewGroup {
                open class LayoutParams
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/Hikage.kt",
            """
            package com.highcapable.hikage.core

            import android.view.ViewGroup

            class Hikage {
                class Performer<LP : ViewGroup.LayoutParams>
                class Attribute
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/attribute/AttributeScope.kt",
            """
            package com.highcapable.hikage.core.attribute

            import com.highcapable.hikage.core.Hikage

            interface AttributeScope {
                fun set(name: String, value: String)
            }

            fun Hikage.Attribute.android(block: AttributeScope.() -> Unit) = Unit
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/widget/android/widget/TextView.kt",
            """
            package com.highcapable.hikage.widget.android.widget

            import android.view.ViewGroup
            import com.highcapable.hikage.core.Hikage

            fun <LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.TextView(
                attrs: Hikage.Attribute.() -> Unit = {}
            ) = Unit
            """.trimIndent()
        )
        val target = configureKotlinByText(
            "PasteTarget.kt",
            """
            package com.highcapable.hikage.fixture

            import android.view.ViewGroup
            import com.highcapable.hikage.core.Hikage

            class PasteTarget

            fun <LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.target() {
                <caret>
            }
            """.trimIndent()
        )
        KotlinSnippetClipboardOutput.publish(
            project,
            ConversionOutcome(KotlinSnippet(
                code = "TextView(attrs = { android { set(\"text\", \"Hello\") } })",
                imports = listOf(
                    "com.highcapable.hikage.core.attribute.android",
                    "com.highcapable.hikage.widget.android.widget.TextView"
                )
            ))
        )

        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        PlatformTestUtil.waitWithEventsDispatching(
            "Kotlin paste did not restore the exact Performer and attrs imports.",
            {
                target.importDirectives.mapNotNullTo(mutableSetOf()) { directive ->
                    directive.importedFqName?.asString()
                }.containsAll(setOf(
                    "com.highcapable.hikage.core.attribute.android",
                    "com.highcapable.hikage.widget.android.widget.TextView"
                ))
            },
            10
        )

        assertContains(target.text, "TextView(attrs = { android { set(\"text\", \"Hello\") } })")
        assertTrue(target.importDirectives.none { directive -> directive.isAllUnder })
        assertFalse(target.text.contains("import android.view.ViewGroup\nimport android.view.ViewGroup"))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val indexedTarget = KotlinFullClassNameIndex[
            "com.highcapable.hikage.fixture.PasteTarget",
            project,
            GlobalSearchScope.projectScope(project)
        ].single()
        assertTrue(indexedTarget.isValid)
        assertSame(target, indexedTarget.containingKtFile)
    }

    /** Verifies Kotlin paste imports only the target module `R` and keeps an external `R` qualified. */
    fun testClipboardSnippetKeepsExternalResourceClassQualifiedOnKotlinPaste() {
        installAndroidSourceProvider(addLayoutFile("<View/>"))
        addProjectFile(
            "com/highcapable/hikage/fixture/R.kt",
            """
            package com.highcapable.hikage.fixture

            object R {
                object drawable {
                    const val hikageIcon = 1
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/theme/R.kt",
            """
            package com.highcapable.hikage.fixture.theme

            object R {
                object attr {
                    const val dimensionHikageSpacingPrimary = 1
                }
            }
            """.trimIndent()
        )
        val target = configureKotlinByText(
            "QualifiedResourcePasteTarget.kt",
            """
            package com.highcapable.hikage.fixture.output

            fun target() {
                <caret>
            }
            """.trimIndent()
        )
        KotlinSnippetClipboardOutput.publish(
            project,
            ConversionOutcome(KotlinSnippet(
                code = """
                    val icon = R.drawable.hikageIcon
                    val spacing = com.highcapable.hikage.fixture.theme.R.attr.dimensionHikageSpacingPrimary
                """.trimIndent(),
                imports = listOf("com.highcapable.hikage.fixture.R"),
                unqualifiedResourceClassName = "com.highcapable.hikage.fixture.R"
            ))
        )

        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        PlatformTestUtil.waitWithEventsDispatching(
            "Kotlin paste did not restore the current module resource import.",
            {
                target.importDirectives.any { directive ->
                    directive.importedFqName?.asString() == "com.highcapable.hikage.fixture.R" &&
                        directive.aliasName == null
                }
            },
            10
        )

        assertContains(target.text, "import com.highcapable.hikage.fixture.R")
        assertContains(target.text, "com.highcapable.hikage.fixture.theme.R.attr.dimensionHikageSpacingPrimary")
        assertTrue(target.importDirectives.none { directive ->
            directive.importedFqName?.asString() == "com.highcapable.hikage.fixture.theme.R"
        })
        assertTrue(target.importDirectives.none { directive -> directive.aliasName != null })
        assertTrue(target.importDirectives.none { directive -> directive.isAllUnder })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoPsiErrors(target)
    }

    /** Verifies a source-module `R` becomes qualified when pasted into another Android module. */
    fun testClipboardSnippetQualifiesSourceResourceClassForAnotherPasteTarget() {
        installAndroidSourceProvider(addLayoutFile("<View/>"))
        addProjectFile(
            "com/highcapable/hikage/fixture/R.kt",
            """
            package com.highcapable.hikage.fixture

            object R
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/library/R.kt",
            """
            package com.highcapable.hikage.library

            object R {
                object string {
                    const val search_bar_hint = 1
                }
            }
            """.trimIndent()
        )
        val target = configureKotlinByText(
            "CrossModuleResourcePasteTarget.kt",
            """
            package com.highcapable.hikage.fixture.output

            import com.highcapable.hikage.fixture.R

            fun target() {
                <caret>
            }
            """.trimIndent()
        )
        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "com.highcapable.hikage.fixture.SearchEditText",
            call = KotlinLayoutCall(
                functionName = "SearchEditText",
                importName = "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.SearchEditText",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(KotlinLayoutInitializer(
                memberName = "hint",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(value =
                    KotlinLayoutInitializer.Value.Resource(
                        resourceClassName = "com.highcapable.hikage.library.R",
                        resourceType = "string",
                        resourceName = "search_bar_hint",
                        helperName = "textResource"
                    )
                ))
            )),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals("com.highcapable.hikage.library.R", snippet.unqualifiedResourceClassName)
        KotlinSnippetClipboardOutput.publish(
            project,
            ConversionOutcome(snippet)
        )

        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        PlatformTestUtil.waitWithEventsDispatching(
            "Kotlin paste did not qualify the source-module resource class.",
            {
                target.text.contains("com.highcapable.hikage.library.R.string.search_bar_hint")
            },
            10
        )

        assertContains(target.text, "import com.highcapable.hikage.fixture.R")
        assertContains(
            target.text,
            "hint = textResource(com.highcapable.hikage.library.R.string.search_bar_hint)"
        )
        assertTrue(target.importDirectives.none { directive ->
            directive.importedFqName?.asString() == "com.highcapable.hikage.library.R"
        })
        assertEquals(1, target.importDirectives.count { directive ->
            directive.importedFqName?.asString()?.endsWith(".R") == true
        })
        assertTrue(target.importDirectives.none { directive -> directive.aliasName != null })
        assertTrue(target.importDirectives.none { directive -> directive.isAllUnder })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoPsiErrors(target)
    }

    /** Verifies disabling imports on paste keeps the snippet text but does not add its metadata imports. */
    fun testClipboardSnippetRespectsDisabledImportsOnPaste() {
        val settings = CodeInsightSettings.getInstance()
        val previousSetting = settings.ADD_IMPORTS_ON_PASTE
        try {
            settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.NO
            val target = configureKotlinByText(
                "PasteWithoutImports.kt",
                """
                package com.highcapable.hikage.fixture

                fun target() {
                    <caret>
                }
                """.trimIndent()
            )
            KotlinSnippetClipboardOutput.publish(
                project,
                ConversionOutcome(KotlinSnippet(
                    code = "TextView()",
                    imports = listOf("com.highcapable.hikage.widget.android.widget.TextView")
                ))
            )

            myFixture.performEditorAction(IdeActions.ACTION_PASTE)
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            assertContains(target.text, "TextView()")
            assertTrue(target.importDirectives.isEmpty())
        } finally {
            settings.ADD_IMPORTS_ON_PASTE = previousSetting
        }
    }

    private fun addLayoutFile(source: String) =
        addProjectFile("app/src/main/res/layout/activity_main.xml", source) as XmlFile

    private fun installAndroidSourceProvider(layout: PsiFile): AndroidFacet {
        val manifest = addProjectFile(
            "app/src/main/AndroidManifest.xml",
            "<manifest package=\"com.highcapable.hikage.fixture\"/>"
        )
        val resourceDirectory = requireNotNull(layout.virtualFile.parent?.parent)
        val facet = AndroidFacet.getInstance(module) ?: WriteAction.compute<AndroidFacet, RuntimeException> {
            FacetManager.getInstance(module).addFacet(AndroidFacet.getFacetType(), AndroidFacet.NAME, null)
        }
        val sourceProvider = NamedIdeaSourceProviderBuilder.create("main", manifest.virtualFile.url)
            .withScopeType(ScopeType.MAIN)
            .withResDirectoryUrls(listOf(resourceDirectory.url))
            .build()
        SourceProviders.replaceForTest(facet, testRootDisposable, sourceProvider)
        return facet
    }

    private fun performerDeclarations() = listOf(
        performer("android.widget.LinearLayout", isViewGroup = true),
        performer("android.widget.TextView", isViewGroup = false)
    )

    private fun performer(
        viewClass: String,
        isViewGroup: Boolean,
        lparams: String? = if (isViewGroup) "android.view.ViewGroup.LayoutParams" else null
    ): PerformerDeclaration {
        val declaration = requireNotNull(ViewDeclaration.from(viewClass, alias = null, isViewGroup = isViewGroup))
        return PerformerDeclaration(
            spec = PerformerSpec(
                lparams = lparams,
                attrs = true,
                init = true,
                performer = isViewGroup
            ),
            declaration = declaration,
            source = PerformerDeclaration.Source.OPTIONAL_FILE
        )
    }

    private fun installGenericFallbackTestApi() {
        addProjectFile(
            "sample/FallbackContainer.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.ViewGroup

            class FallbackContainer(
                context: Context,
                attrs: AttributeSet?
            ) : ViewGroup(context, attrs) {
                class LayoutParams : ViewGroup.LayoutParams()

                fun generateDefaultLayoutParams() = LayoutParams()
            }
            """.trimIndent()
        )
        addProjectFile(
            "sample/FallbackView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class FallbackView(
                context: Context,
                attrs: AttributeSet?
            ) : View(context, attrs)
            """.trimIndent()
        )
    }

    private fun installRepresentativeAndroidWidgetApi() {
        addProjectFile(
            "android/R.java",
            """
            package android;

            public final class R {
                public static final class drawable {
                    public static final int ic_menu_gallery = 1;
                    public static final int ic_menu_search = 2;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/R.java",
            """
            package com.highcapable.hikage.fixture;

            public final class R {
                public static final class color {
                    public static final int black = 1;
                    public static final int white = 2;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/annotation/IntDef.java",
            """
            package android.annotation;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.SOURCE)
            @Target(ElementType.ANNOTATION_TYPE)
            public @interface IntDef {
                long[] value() default {};
                boolean flag() default false;
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/annotation/ColorInt.java",
            """
            package android.annotation;

            public @interface ColorInt {}
            """.trimIndent()
        )
        addProjectFile(
            "android/annotation/DrawableRes.java",
            """
            package android.annotation;

            public @interface DrawableRes {}
            """.trimIndent()
        )
        addProjectFile(
            "android/content/res/ColorStateList.java",
            """
            package android.content.res;

            public class ColorStateList {}
            """.trimIndent()
        )
        addProjectFile(
            "android/view/inspector/InspectableProperty.java",
            """
            package android.view.inspector;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.METHOD, ElementType.FIELD})
            public @interface InspectableProperty {
                String name() default "";
                ValueType valueType() default ValueType.NONE;
                EnumEntry[] enumMapping() default {};
                FlagEntry[] flagMapping() default {};

                enum ValueType {
                    NONE,
                    GRAVITY
                }

                @interface EnumEntry {
                    int value();
                    String name();
                }

                @interface FlagEntry {
                    int mask();
                    int target();
                    String name();
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/view/Gravity.java",
            """
            package android.view;

            import android.annotation.IntDef;

            public final class Gravity {
                public static final int CENTER_VERTICAL = 16;
                public static final int CENTER = 17;

                @IntDef({CENTER_VERTICAL, CENTER})
                public @interface GravityFlags {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/LinearLayout.java",
            """
            package android.widget;

            import android.annotation.IntDef;
            import android.view.Gravity;
            import android.view.ViewGroup;
            import android.view.inspector.InspectableProperty;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            public class LinearLayout extends ViewGroup {
                public static final int HORIZONTAL = 0;
                public static final int VERTICAL = 1;

                @IntDef({HORIZONTAL, VERTICAL})
                @Retention(RetentionPolicy.SOURCE)
                public @interface OrientationMode {}

                @InspectableProperty(enumMapping = {
                    @InspectableProperty.EnumEntry(value = HORIZONTAL, name = "horizontal"),
                    @InspectableProperty.EnumEntry(value = VERTICAL, name = "vertical")
                })
                public int getOrientation() {
                    return HORIZONTAL;
                }

                public void setOrientation(@OrientationMode int orientation) {}

                @InspectableProperty(
                    valueType = InspectableProperty.ValueType.GRAVITY,
                    flagMapping = {
                        @InspectableProperty.FlagEntry(mask = -1, target = Gravity.CENTER_VERTICAL,
                            name = "center_vertical"),
                        @InspectableProperty.FlagEntry(mask = -1, target = Gravity.CENTER,
                            name = "center")
                    }
                )
                public int getGravity() {
                    return Gravity.CENTER_VERTICAL;
                }

                public void setGravity(int gravity) {}

                public static class LayoutParams extends ViewGroup.MarginLayoutParams {
                    public static final int MATCH_PARENT = -1;
                    public static final int WRAP_CONTENT = -2;

                    @InspectableProperty(
                        name = "layout_width",
                        enumMapping = {
                            @InspectableProperty.EnumEntry(value = MATCH_PARENT, name = "match_parent"),
                            @InspectableProperty.EnumEntry(value = WRAP_CONTENT, name = "wrap_content")
                        }
                    )
                    public int width;

                    @InspectableProperty(
                        name = "layout_height",
                        enumMapping = {
                            @InspectableProperty.EnumEntry(value = MATCH_PARENT, name = "match_parent"),
                            @InspectableProperty.EnumEntry(value = WRAP_CONTENT, name = "wrap_content")
                        }
                    )
                    public int height;

                    @InspectableProperty(name = "layout_weight")
                    public float weight;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/TextView.java",
            """
            package android.widget;

            import android.annotation.ColorInt;
            import android.content.res.ColorStateList;
            import android.view.Gravity;
            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class TextView extends View {
                @InspectableProperty
                public CharSequence getText() {
                    return null;
                }

                public void setText(CharSequence text) {}

                @InspectableProperty
                public CharSequence getHint() {
                    return null;
                }

                public void setHint(CharSequence hint) {}

                /** @attr ref android.R.styleable#TextView_textColor */
                public void setTextColor(@ColorInt int color) {}

                /** @attr ref android.R.styleable#TextView_textColor */
                public void setTextColor(ColorStateList colors) {}

                @InspectableProperty(name = "textColor")
                public ColorStateList getTextColors() {
                    return null;
                }

                @InspectableProperty(
                    valueType = InspectableProperty.ValueType.GRAVITY,
                    flagMapping = {
                        @InspectableProperty.FlagEntry(mask = -1, target = Gravity.CENTER_VERTICAL,
                            name = "center_vertical"),
                        @InspectableProperty.FlagEntry(mask = -1, target = Gravity.CENTER,
                            name = "center")
                    }
                )
                public int getGravity() {
                    return Gravity.CENTER_VERTICAL;
                }

                public void setGravity(int gravity) {}

                @InspectableProperty
                public boolean isSingleLine() {
                    return false;
                }

                public void setSingleLine(boolean singleLine) {}

                @InspectableProperty(name = "textAllCaps")
                public boolean isAllCaps() {
                    return false;
                }

                public void setAllCaps(boolean allCaps) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/Button.java",
            """
            package android.widget;

            import android.view.inspector.InspectableProperty;

            public class Button extends TextView {
                @InspectableProperty
                public boolean isEnabled() {
                    return true;
                }

                public void setEnabled(boolean enabled) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/EditText.java",
            """
            package android.widget;

            public class EditText extends TextView {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/CompoundButton.java",
            """
            package android.widget;

            import android.view.inspector.InspectableProperty;

            public class CompoundButton extends Button {
                @InspectableProperty
                public boolean isChecked() {
                    return false;
                }

                public void setChecked(boolean checked) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/CheckBox.java",
            """
            package android.widget;

            public class CheckBox extends CompoundButton {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/Switch.java",
            """
            package android.widget;

            public class Switch extends CompoundButton {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/ImageView.java",
            """
            package android.widget;

            import android.annotation.DrawableRes;
            import android.view.View;

            public class ImageView extends View {
                /** @attr ref android.R.styleable#ImageView_src */
                public void setImageResource(@DrawableRes int resourceId) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/ImageButton.java",
            """
            package android.widget;

            public class ImageButton extends ImageView {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/ProgressBar.java",
            """
            package android.widget;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class ProgressBar extends View {
                @InspectableProperty
                public boolean isIndeterminate() {
                    return false;
                }

                public void setIndeterminate(boolean indeterminate) {}

                @InspectableProperty
                public int getMax() {
                    return 0;
                }

                public void setMax(int max) {}

                @InspectableProperty
                public int getProgress() {
                    return 0;
                }

                public void setProgress(int progress) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/RatingBar.java",
            """
            package android.widget;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class RatingBar extends View {
                @InspectableProperty
                public boolean isIndicator() {
                    return false;
                }

                public void setIsIndicator(boolean indicator) {}

                @InspectableProperty
                public int getNumStars() {
                    return 0;
                }

                public void setNumStars(int stars) {}

                @InspectableProperty
                public float getRating() {
                    return 0;
                }

                public void setRating(float rating) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/Space.java",
            """
            package android.widget;

            import android.view.View;

            public class Space extends View {}
            """.trimIndent()
        )
    }

    private fun captureNotification(block: () -> Unit): Notification {
        val notifications = mutableListOf<Notification>()
        val connection = project.messageBus.connect()
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
        try {
            block()
            return notifications.single()
        } finally {
            connection.disconnect()
        }
    }
}