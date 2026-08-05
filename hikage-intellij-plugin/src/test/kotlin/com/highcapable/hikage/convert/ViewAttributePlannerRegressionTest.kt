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
 * This file is created by fankes on 2026/7/30.
 */
package com.highcapable.hikage.convert

import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.aar.AarSourceResourceRepository
import com.android.tools.dom.attrs.AttributeDefinition
import com.android.tools.dom.attrs.AttributeDefinitionsImpl
import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.ScopeType
import com.android.tools.idea.projectsystem.SourceProviders
import com.highcapable.hikage.convert.generator.PerformerSnippetRenderer
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionSource
import com.highcapable.hikage.convert.model.KotlinLayoutCall
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutNode
import com.highcapable.hikage.convert.model.ViewConversionOption
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.convert.parser.XmlLayoutParser
import com.highcapable.hikage.convert.planner.SpacingPlanner
import com.highcapable.hikage.convert.planner.ThemeAttributePlanner
import com.highcapable.hikage.convert.planner.ViewAttributePlanner
import com.highcapable.hikage.convert.resolver.XmlLayoutModelResolver
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerSpec
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.project.model.android.AndroidResourceOwnerResolver
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.assertNoErrorLogged
import org.jetbrains.android.facet.AndroidFacet
import java.io.File

/**
 * Verifies the conservative PSI proof boundary for generated View `init` writes.
 */
class ViewAttributePlannerRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies exact inspectable members enter `init` while an unproven peer remains in `attrs`. */
    fun testPreferInitUsesInspectablePropertyAndFallsBackPerAttribute() {
        installHikageTestApi()
        installInspectablePropertyApi()
        addProjectFile(
            "sample/InspectableView.java",
            """
            package sample;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class InspectableView extends View {
                private CharSequence title;

                @InspectableProperty(name = "title")
                public CharSequence getTitle() {
                    return title;
                }

                public void setTitle(CharSequence title) {
                    this.title = title;
                }

                public void setUnproven(CharSequence unproven) {}
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.InspectableView xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:title="Hello"
                app:unproven="keep"/>
            """.trimIndent()
        )

        val snippet = convert(layout, performer("sample.InspectableView"), ViewConversionOption.COMPATIBLE_MODE)

        assertEquals(
            """
            InspectableView(
                lparams = LayoutParams(),
                attrs = {
                    app {
                        set("unproven", "keep")
                    }
                }
            ) {
                title = "Hello"
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.hikage.core.attribute.app",
                "com.highcapable.hikage.core.layout.LayoutParams",
                "com.highcapable.hikage.widget.sample.InspectableView"
            ),
            snippet.imports
        )
        assertFalse(snippet.code.contains(",\n            )"))
    }

    /** Verifies Kotlin getter annotations prove writable project properties without name guessing. */
    fun testPreferInitUsesKotlinInspectableProperty() {
        installHikageTestApi()
        installInspectablePropertyApi()
        addProjectFile(
            "sample/KotlinInspectableView.kt",
            """
            package sample

            import android.view.View
            import android.view.inspector.InspectableProperty

            class KotlinInspectableView : View() {

                @get:InspectableProperty(name = "title")
                var title: CharSequence = ""
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.KotlinInspectableView xmlns:app="http://schemas.android.com/apk/res-auto"
                app:title="From Kotlin"/>
            """.trimIndent()
        )

        val snippet = convert(layout, performer("sample.KotlinInspectableView"), ViewConversionOption.COMPATIBLE_MODE)

        assertEquals(
            """
            KotlinInspectableView {
                title = "From Kotlin"
            }
            """.trimIndent(),
            snippet.code
        )
        assertTrue(snippet.imports.none { importName -> importName.contains(".attribute.") })
    }

    /** Verifies a package-less dependency attr keeps its declaring R class in member and padding initialization. */
    fun testPackageLessDependencyThemeDimensionUsesDeclaringLibraryResourcePackage() {
        installHikageTestApi()
        installInspectablePropertyApi()
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
        val viewClass = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.ThemeDimensionView",
            GlobalSearchScope.projectScope(project)
        ))
        val attribute = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res-auto",
            namespacePrefix = "app",
            localName = "spacing",
            qualifiedName = "app:spacing",
            rawValue = "?dimensionHikageSpacingPrimary",
            value = "?dimensionHikageSpacingPrimary",
            source = ConversionSource("file:///theme_dimension.xml", TextRange.EMPTY_RANGE)
        )
        val targetDefinition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.RES_AUTO,
            "spacing",
            null,
            setOf(AttributeFormat.DIMENSION)
        ))
        val libraryRoot = FileUtil.createTempDirectory("hikage-theme-resources", "", true)
        libraryRoot.resolve("AndroidManifest.xml").writeText(
            "<manifest package=\"com.highcapable.hikage.fixture.theme\"/>"
        )
        val resourceRoot = libraryRoot.resolve("res")
        resourceRoot.resolve("values/attrs.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <resources>
                    <attr name="dimensionHikageSpacingPrimary" format="dimension"/>
                </resources>
                """.trimIndent()
            )
        }
        val resourceRepository = AarSourceResourceRepository.createForTest(
            resourceRoot.toPath(),
            ResourceNamespace.RES_AUTO,
            "hikage-theme"
        )
        val dependencyDefinition = requireNotNull(
            AttributeDefinitionsImpl.create(null, resourceRepository).getAttrDefinition(
                ResourceReference.attr(ResourceNamespace.RES_AUTO, "dimensionHikageSpacingPrimary")
            )
        )
        assertNull(dependencyDefinition.resourceReference.namespace.packageName)
        val declarationPackageName = requireNotNull(AndroidResourceOwnerResolver.resolvePackageName(
            dependencyDefinition,
            resourceRepository
        ))
        assertEquals("com.highcapable.hikage.fixture.theme", declarationPackageName)
        val packageLessDependencyDefinition = AttributeDefinition(
            ResourceNamespace.RES_AUTO,
            dependencyDefinition.name,
            null,
            dependencyDefinition.formats
        )
        val themeDefinition = AndroidAttributeResolver.Attribute(
            definition = packageLessDependencyDefinition,
            declarationPackageName = declarationPackageName
        )
        assertNull(packageLessDependencyDefinition.libraryName)
        assertNull(ThemeAttributePlanner.planIntegerDimension(
            rawValue = attribute.value,
            definition = themeDefinition.copy(definition = AttributeDefinition(
                ResourceNamespace.RES_AUTO,
                themeDefinition.name,
                null,
                setOf(AttributeFormat.STRING)
            )),
            currentModuleResourcePackageName = "com.highcapable.hikage.fixture"
        ))
        val initializer = requireNotNull(ViewAttributePlanner.plan(
            attribute = attribute,
            namespace = "app",
            viewClass = viewClass,
            definition = targetDefinition,
            resourcePackageName = "com.highcapable.hikage.fixture",
            themeAttributeDefinition = themeDefinition
        ))
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "spacing",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.ThemeAttribute(
                        resourceClassName = "com.highcapable.hikage.fixture.theme.R",
                        resourceName = "dimensionHikageSpacingPrimary",
                        functionName = "getThemeAttrsDimension",
                        importName = "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                        isCurrentModuleResource = false
                    )
                ))
            ),
            initializer
        )
        assertNull(ViewAttributePlanner.plan(
            attribute = attribute,
            namespace = "app",
            viewClass = viewClass,
            definition = targetDefinition,
            resourcePackageName = "com.highcapable.hikage.fixture"
        ))
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
            initializers = listOf(initializer),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals(
            """
            ThemeDimensionView {
                spacing = context.getThemeAttrsDimension(com.highcapable.hikage.fixture.theme.R.attr.dimensionHikageSpacingPrimary)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.ThemeDimensionView"
            ),
            snippet.imports
        )
        val paddingAttribute = attribute.copy(
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "paddingBottom",
            qualifiedName = "android:paddingBottom"
        )
        val paddingThemeValue = requireNotNull(ThemeAttributePlanner.planIntegerDimension(
            rawValue = paddingAttribute.value,
            definition = themeDefinition,
            currentModuleResourcePackageName = "com.highcapable.hikage.fixture"
        ))
        val paddingPlan = SpacingPlanner.planPadding(listOf(paddingAttribute)) { paddingThemeValue }
        val paddingSnippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.widget.TextView",
            call = KotlinLayoutCall(
                functionName = "TextView",
                importName = "com.highcapable.hikage.widget.android.widget.TextView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = paddingPlan.initializers,
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertTrue(paddingPlan.isConverted)
        assertEquals(
            """
            TextView {
                updatePadding(bottom = context.getThemeAttrsDimension(com.highcapable.hikage.fixture.theme.R.attr.dimensionHikageSpacingPrimary).toInt())
            }
            """.trimIndent(),
            paddingSnippet.code
        )
        assertEquals(
            listOf(
                "androidx.core.view.updatePadding",
                "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            paddingSnippet.imports
        )
    }

    /** Verifies every canonical padding shape accepts the same proven runtime theme-dimension value. */
    fun testThemeDimensionPaddingUsesContextExtensionForAllAxesAndSides() {
        val themeName = "dimensionHikageSpacingPrimary"
        val resourcePackageName = "com.highcapable.hikage.fixture"
        val themeDefinition = AndroidAttributeResolver.Attribute(
            definition = AttributeDefinition(
                ResourceNamespace.RES_AUTO,
                themeName,
                null,
                setOf(AttributeFormat.DIMENSION)
            ),
            declarationPackageName = resourcePackageName
        )
        val runtimeDimension = requireNotNull(ThemeAttributePlanner.planIntegerDimension(
            rawValue = "?$themeName",
            definition = themeDefinition,
            currentModuleResourcePackageName = resourcePackageName
        ))
        val source = ConversionSource("file:///theme_padding.xml", TextRange.EMPTY_RANGE)

        fun padding(name: String) = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = name,
            qualifiedName = "android:$name",
            rawValue = "?$themeName",
            value = "?$themeName",
            source = source
        )

        fun textView(vararg names: String): KotlinLayoutNode {
            val plan = SpacingPlanner.planPadding(names.map(::padding)) { runtimeDimension }
            assertTrue(plan.isConverted)
            return KotlinLayoutNode(
                viewClassName = "android.widget.TextView",
                call = KotlinLayoutCall(
                    functionName = "TextView",
                    importName = "com.highcapable.hikage.widget.android.widget.TextView",
                    hasChildPerformerParameter = false
                ),
                layoutParams = null,
                id = null,
                attributes = emptyList(),
                initializers = plan.initializers,
                todoAttributes = emptyList(),
                todoComments = emptyList(),
                children = emptyList()
            )
        }

        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.widget.LinearLayout",
            call = KotlinLayoutCall(
                functionName = "LinearLayout",
                importName = "com.highcapable.hikage.widget.android.widget.LinearLayout",
                hasChildPerformerParameter = true
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = emptyList(),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = listOf(
                textView("padding"),
                textView("paddingHorizontal"),
                textView("paddingVertical"),
                textView("paddingLeft", "paddingTop", "paddingRight", "paddingBottom"),
                textView("paddingStart", "paddingTop", "paddingEnd", "paddingBottom")
            )
        ))
        val themeDimension = "context.getThemeAttrsDimension(R.attr.$themeName).toInt()"

        assertContains(snippet.code, "updatePadding(horizontal = $themeDimension, vertical = $themeDimension)")
        assertContains(snippet.code, "updatePadding(horizontal = $themeDimension)")
        assertContains(snippet.code, "updatePadding(vertical = $themeDimension)")
        assertContains(snippet.code,
            "updatePadding(left = $themeDimension, top = $themeDimension, right = $themeDimension, " +
                "bottom = $themeDimension)")
        assertContains(snippet.code,
            "updatePaddingRelative(start = $themeDimension, top = $themeDimension, end = $themeDimension, " +
                "bottom = $themeDimension)")
        assertEquals(12, "context\\.getThemeAttrsDimension".toRegex().findAll(snippet.code).count())
        assertEquals(
            listOf(
                "androidx.core.view.updatePadding",
                "androidx.core.view.updatePaddingRelative",
                "com.highcapable.betterandroid.ui.extension.component.base.getThemeAttrsDimension",
                "com.highcapable.betterandroid.ui.extension.view.updatePadding",
                "com.highcapable.hikage.fixture.R",
                "com.highcapable.hikage.widget.android.widget.LinearLayout",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
    }

    /** Verifies a Drawable property accepts color resources only when Android's attribute format proves it. */
    fun testInspectableDrawableUsesColorResourceWhenAttributeAcceptsColor() {
        installHikageTestApi()
        installInspectablePropertyApi()
        addProjectFile(
            "android/graphics/drawable/Drawable.java",
            """
            package android.graphics.drawable;

            public class Drawable {}
            """.trimIndent()
        )
        addProjectFile(
            "android/view/BackgroundView.java",
            """
            package android.view;

            import android.graphics.drawable.Drawable;
            import android.view.inspector.InspectableProperty;

            public class BackgroundView extends View {
                @InspectableProperty
                public Drawable getBackground() {
                    return null;
                }

                public void setBackground(Drawable background) {}
            }
            """.trimIndent()
        )
        val viewClass = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.view.BackgroundView",
            GlobalSearchScope.projectScope(project)
        ))
        val attribute = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "background",
            qualifiedName = "android:background",
            rawValue = "@color/black",
            value = "@color/black",
            source = ConversionSource("file:///background.xml", TextRange.EMPTY_RANGE)
        )
        val colorDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "background",
            null,
            setOf(AttributeFormat.REFERENCE, AttributeFormat.COLOR)
        )
        val referenceDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "background",
            null,
            setOf(AttributeFormat.REFERENCE)
        )
        val initializer = requireNotNull(ViewAttributePlanner.plan(
            attribute,
            "android",
            viewClass,
            AndroidAttributeResolver.Attribute(colorDefinition),
            "com.highcapable.hikage.fixture"
        ))

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "background",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.ExtensionCall(
                        receiver = KotlinLayoutInitializer.Value.Resource(
                            resourceClassName = "com.highcapable.hikage.fixture.R",
                            resourceType = "color",
                            resourceName = "black",
                            helperName = "colorResource"
                        ),
                        importName = "androidx.core.graphics.drawable.toDrawable",
                        functionName = "toDrawable"
                    )
                ))
            ),
            initializer
        )
        assertNull(ViewAttributePlanner.plan(
            attribute,
            "android",
            viewClass,
            AndroidAttributeResolver.Attribute(referenceDefinition),
            "com.highcapable.hikage.fixture"
        ))
        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.view.BackgroundView",
            call = KotlinLayoutCall(
                functionName = "BackgroundView",
                importName = "com.highcapable.hikage.widget.android.view.BackgroundView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(initializer),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals(
            """
            BackgroundView {
                background = colorResource(R.color.black).toDrawable()
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "androidx.core.graphics.drawable.toDrawable",
                "com.highcapable.hikage.fixture.R",
                "com.highcapable.hikage.widget.android.view.BackgroundView"
            ),
            snippet.imports
        )
    }

    /** Verifies a documented multi-parameter setter can reuse one same-property getter without an attribute-name rule. */
    fun testDocumentedMultiParameterSetterUsesProvenStateGetterAndValueContract() {
        installInspectablePropertyMetadataApi()
        addProjectFile(
            "android/graphics/Typeface.java",
            """
            package android.graphics;

            import android.annotation.IntDef;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            public class Typeface {
                public static final int NORMAL = 0;
                public static final int BOLD = 1;
                public static final int ITALIC = 2;
                public static final int BOLD_ITALIC = 3;

                @IntDef(value = {NORMAL, BOLD, ITALIC, BOLD_ITALIC}, flag = true)
                @Retention(RetentionPolicy.SOURCE)
                public @interface Style {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/StyledTextView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.graphics.Typeface;

            public class StyledTextView {
                public Typeface getTypeface() {
                    return null;
                }

                public void setTypeface(Typeface typeface) {}

                /**
                 * @attr ref android.R.styleable#TextView_typeface
                 * @attr ref android.R.styleable#TextView_textStyle
                 */
                public void setTypeface(Typeface typeface, @Typeface.Style int style) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/PresentationView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.graphics.Typeface;

            public class PresentationView {
                public Typeface getPresentation() {
                    return null;
                }

                /** @attr ref android.R.styleable#TextView_emphasis */
                public void setPresentation(Typeface presentation, @Typeface.Style int emphasis) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/UnprovenStyleView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.graphics.Typeface;

            public class UnprovenStyleView {
                public Typeface getTypeface() {
                    return null;
                }

                /** @attr ref android.R.styleable#TextView_textStyle */
                public void setTypeface(Typeface typeface, int style) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/MissingStateStyleView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.graphics.Typeface;

            public class MissingStateStyleView {
                public Typeface getCurrentTypeface() {
                    return null;
                }

                /** @attr ref android.R.styleable#TextView_textStyle */
                public void setTypeface(Typeface typeface, @Typeface.Style int style) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/fixture/AmbiguousStyleView.java",
            """
            package com.highcapable.hikage.fixture;

            import android.graphics.Typeface;

            public class AmbiguousStyleView {
                public Typeface getTypeface() {
                    return null;
                }

                /** @attr ref android.R.styleable#TextView_textStyle */
                public void setTypeface(Typeface typeface, @Typeface.Style int style) {}

                /** @attr ref android.R.styleable#TextView_textStyle */
                public void setTypeface(Typeface typeface, @Typeface.Style long style) {}
            }
            """.trimIndent()
        )
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        val styledTextView = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.StyledTextView",
            scope
        ))
        val presentationView = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.PresentationView",
            scope
        ))
        val unprovenStyleView = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.UnprovenStyleView",
            scope
        ))
        val missingStateStyleView = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.MissingStateStyleView",
            scope
        ))
        val ambiguousStyleView = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.AmbiguousStyleView",
            scope
        ))
        val source = ConversionSource("file:///styled-text.xml", TextRange.EMPTY_RANGE)
        fun attribute(name: String, value: String) = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = AndroidSymbols.NAMESPACE_URI,
            namespacePrefix = "android",
            localName = name,
            qualifiedName = "android:$name",
            rawValue = value,
            value = value,
            source = source
        )
        fun definition(name: String) = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            name,
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply {
            setValueMappings(mapOf("normal" to 0, "bold" to 1, "italic" to 2))
        })
        fun constant(name: String) = KotlinLayoutInitializer.Value.SymbolicConstant(
            importName = "android.graphics.Typeface",
            qualifier = "Typeface",
            memberName = name
        )
        val expectations = mapOf(
            "normal" to constant("NORMAL"),
            "bold" to constant("BOLD"),
            "italic" to constant("ITALIC"),
            "bold|italic" to KotlinLayoutInitializer.Value.BitwiseOr(listOf(
                constant("BOLD"),
                constant("ITALIC")
            ))
        )

        val planned = expectations.mapValues { (rawValue, expectedValue) ->
            val initializer = requireNotNull(ViewAttributePlanner.plan(
                attribute("textStyle", rawValue),
                "android",
                styledTextView,
                definition("textStyle"),
                null
            ))
            assertEquals(
                KotlinLayoutInitializer(
                    memberName = "setTypeface",
                    memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                    arguments = listOf(
                        KotlinLayoutInitializer.Argument(
                            value = KotlinLayoutInitializer.Value.ReceiverProperty("typeface")
                        ),
                        KotlinLayoutInitializer.Argument(value = expectedValue)
                    )
                ),
                initializer
            )
            initializer
        }
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setPresentation",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(
                    KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.ReceiverProperty("presentation")
                    ),
                    KotlinLayoutInitializer.Argument(value = constant("BOLD"))
                )
            ),
            ViewAttributePlanner.plan(
                attribute("emphasis", "bold"),
                "android",
                presentationView,
                definition("emphasis"),
                null
            )
        )
        val typefaceDefinition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            "typeface",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply {
            setValueMappings(mapOf("normal" to 0, "sans" to 1, "serif" to 2, "monospace" to 3))
        })
        assertNull(ViewAttributePlanner.plan(
            attribute("typeface", "normal"),
            "android",
            styledTextView,
            typefaceDefinition,
            null
        ))
        assertNull(ViewAttributePlanner.plan(
            attribute("textStyle", "bold"),
            "android",
            unprovenStyleView,
            definition("textStyle"),
            null
        ))
        assertNull(ViewAttributePlanner.plan(
            attribute("textStyle", "bold"),
            "android",
            missingStateStyleView,
            definition("textStyle"),
            null
        ))
        assertNull(ViewAttributePlanner.plan(
            attribute("textStyle", "bold"),
            "android",
            ambiguousStyleView,
            definition("textStyle"),
            null
        ))

        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "com.highcapable.hikage.fixture.StyledTextView",
            call = KotlinLayoutCall(
                functionName = "StyledTextView",
                importName = "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.StyledTextView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(requireNotNull(planned["bold|italic"])),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals(
            """
            StyledTextView {
                setTypeface(typeface, Typeface.BOLD or Typeface.ITALIC)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "android.graphics.Typeface",
                "com.highcapable.hikage.widget.com.highcapable.hikage.fixture.StyledTextView"
            ),
            snippet.imports
        )
    }

    /** Verifies parsed framework `textStyle` survives when its nested `@IntDef` exists only in attached SDK source. */
    fun testFrameworkTextStyleUsesSourceOnlyNestedIntDef() {
        val textView = installBinaryTextViewWithSourceOnlyStyle()
        val setter = textView.findMethodsByName("setTypeface", false)
            .single { method -> method.parameterList.parametersCount == 2 }
        val sourceSetter = setter.navigationElement as PsiMethod
        val sourceStyleAnnotation = sourceSetter.parameterList.parameters[1].annotations.single()
        assertTrue(setter.parameterList.parameters[1].annotations.isEmpty())
        assertNull(JavaPsiFacade.getInstance(project).findClass(
            "android.graphics.Typeface.Style",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
        assertNull(sourceStyleAnnotation.resolveAnnotationType())
        val layout = addLayoutFile(
            """
            <TextView
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textStyle="bold" />
            """.trimIndent()
        )
        val attribute = requireNotNull(XmlLayoutParser.parse(layout).value)
            .root.attributes.single { candidate -> candidate.localName == "textStyle" }
        val definition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            "textStyle",
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply {
            setValueMappings(mapOf("normal" to 0, "bold" to 1, "italic" to 2))
        })

        val initializer = requireNotNull(ViewAttributePlanner.plan(
            attribute = attribute,
            namespace = "android",
            viewClass = textView,
            definition = definition,
            resourcePackageName = null
        ))
        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.widget.TextView",
            call = KotlinLayoutCall(
                functionName = "TextView",
                importName = "com.highcapable.hikage.widget.android.widget.TextView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(initializer),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))

        assertEquals(
            """
            TextView {
                setTypeface(typeface, Typeface.BOLD)
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "android.graphics.Typeface",
                "com.highcapable.hikage.widget.android.widget.TextView"
            ),
            snippet.imports
        )
    }

    /** Verifies SDK source-only resource annotations remain visible through compiled parameter navigation. */
    fun testDocumentedResourceSetterUsesSourceOnlyParameterAnnotation() {
        val imageView = installBinaryImageViewWithSources()
        val method = imageView.findMethodsByName("setImageResource", false).single()
        val sourceMethod = method.navigationElement as PsiMethod
        assertTrue(method.parameterList.parameters.single().annotations.isEmpty())
        assertEquals(
            "android.annotation.DrawableRes",
            sourceMethod.parameterList.parameters.single().annotations.single().qualifiedName
        )
        val attribute = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "src",
            qualifiedName = "android:src",
            rawValue = "@drawable/sample_icon",
            value = "@drawable/sample_icon",
            source = ConversionSource("file:///sample.xml", TextRange.EMPTY_RANGE)
        )

        val initializer = ViewAttributePlanner.plan(
            attribute = attribute,
            namespace = "android",
            viewClass = imageView,
            definition = null,
            resourcePackageName = "sample"
        )

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setImageResource",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.Resource(
                        resourceClassName = "sample.R",
                        resourceType = "drawable",
                        resourceName = "sample_icon",
                        helperName = null
                    )
                ))
            ),
            initializer
        )
    }

    /** Verifies an exact direct attr documentation link proves only the class that owns the setter. */
    fun testDocumentedDirectAttrResourceSetterDoesNotInferFactoryReplacement() {
        installHikageTestApi()
        addProjectFile(
            "android/annotation/DrawableRes.java",
            """
            package android.annotation;

            public @interface DrawableRes {}
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/ImageView.java",
            """
            package android.widget;

            import android.view.View;

            public class ImageView extends View {}
            """.trimIndent()
        )
        addProjectFile(
            "androidx/appcompat/widget/AppCompatImageView.java",
            """
            package androidx.appcompat.widget;

            import android.annotation.DrawableRes;
            import android.widget.ImageView;

            public class AppCompatImageView extends ImageView {
                /** {@link androidx.appcompat.R.attr#srcCompat} */
                public void setImageResource(@DrawableRes int resourceId) {}
            }
            """.trimIndent()
        )
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        val facade = JavaPsiFacade.getInstance(project)
        val imageView = requireNotNull(facade.findClass("android.widget.ImageView", scope))
        val appCompatImageView = requireNotNull(facade.findClass("androidx.appcompat.widget.AppCompatImageView", scope))
        val attribute = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res-auto",
            namespacePrefix = "app",
            localName = "srcCompat",
            qualifiedName = "app:srcCompat",
            rawValue = "@drawable/sample_icon",
            value = "@drawable/sample_icon",
            source = ConversionSource("file:///sample.xml", TextRange.EMPTY_RANGE)
        )

        assertNull(ViewAttributePlanner.plan(attribute, "app", imageView, null, "sample"))
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setImageResource",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.Resource(
                        resourceClassName = "sample.R",
                        resourceType = "drawable",
                        resourceName = "sample_icon",
                        helperName = null
                    )
                ))
            ),
            ViewAttributePlanner.plan(attribute, "app", appCompatImageView, null, "sample")
        )
    }

    /** Verifies a direct resolved `TypedArray` read-to-setter path produces a method initializer. */
    fun testInitOnlyUsesDirectTypedArraySetterFlow() {
        installHikageTestApi()
        installTypedArrayApi()
        addProjectFile(
            "sample/R.java",
            """
            package sample;

            public final class R {
                public static final class styleable {
                    public static final int SourceView_title = 0;
                    public static final int SourceView_icon = 1;
                    public static final int SourceView_offset = 2;
                }

                public static final class drawable {
                    public static final int sample_icon = 3;
                }

                public static final class dimen {
                    public static final int sample_offset = 4;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "sample/SourceView.java",
            """
            package sample;

            import android.content.res.TypedArray;
            import android.graphics.drawable.Drawable;
            import android.view.View;

            public class SourceView extends View {
                public SourceView() {
                    TypedArray attributes = new TypedArray();
                    setTitle(attributes.getText(R.styleable.SourceView_title));
                    Drawable icon = attributes.getDrawable(R.styleable.SourceView_icon);
                    if (icon != null) {
                        setIcon(icon);
                    }
                    setOffset(attributes.getDimensionPixelOffset(R.styleable.SourceView_offset));
                }

                public void setTitle(CharSequence title) {}
                public void setIcon(Drawable icon) {}
                public void setOffset(int offset) {}
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.SourceView xmlns:app="http://schemas.android.com/apk/res-auto"
                app:title="From source"
                app:icon="@drawable/sample_icon"
                app:offset="@dimen/sample_offset"/>
            """.trimIndent()
        )

        val snippet = convert(layout, performer("sample.SourceView"), ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY)

        assertEquals(
            """
            SourceView {
                setTitle("From source")
                setIcon(drawableResource(R.drawable.sample_icon))
                setOffset(dimenPixelOffsetResource(R.dimen.sample_offset))
            }
            """.trimIndent(),
            snippet.code
        )
        assertTrue(snippet.imports.none { importName -> importName.contains(".attribute.") })
        assertContains(snippet.imports.joinToString(), "sample.R")
    }

    /** Verifies `init` stays named when a child Performer parameter follows it. */
    fun testViewGroupInitStaysInsideParenthesesBeforeChildPerformer() {
        installHikageTestApi()
        installInspectablePropertyApi()
        addProjectFile(
            "sample/InspectableGroup.java",
            """
            package sample;

            import android.view.ViewGroup;
            import android.view.inspector.InspectableProperty;

            public class InspectableGroup extends ViewGroup {
                @InspectableProperty(name = "title")
                public CharSequence getTitle() {
                    return null;
                }

                public void setTitle(CharSequence title) {}
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.InspectableGroup xmlns:app="http://schemas.android.com/apk/res-auto"
                app:title="Hello"/>
            """.trimIndent()
        )

        val snippet = convert(
            layout,
            performer("sample.InspectableGroup", isViewGroup = true),
            ViewConversionOption.COMPATIBLE_MODE
        )

        assertEquals(
            """
            InspectableGroup(
                init = {
                    title = "Hello"
                }
            )
            """.trimIndent(),
            snippet.code
        )
    }

    /** Verifies overload uncertainty and a helper chain cannot be promoted by member-name similarity. */
    fun testPreferInitRejectsAmbiguousSetterAndComplexTypedArrayFlow() {
        installHikageTestApi()
        installInspectablePropertyApi()
        installTypedArrayApi()
        addProjectFile(
            "sample/R.java",
            """
            package sample;

            public final class R {
                public static final class styleable {
                    public static final int UncertainView_complex = 0;
                    public static final int UncertainView_target = 1;
                    public static final int UncertainView_fraction = 2;
                    public static final int UncertainView_wrongGuard = 3;
                    public static final int UncertainView_nullableIcon = 4;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "sample/UncertainView.java",
            """
            package sample;

            import android.content.res.TypedArray;
            import android.graphics.drawable.Drawable;
            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class UncertainView extends View {
                public UncertainView() {
                    TypedArray attributes = new TypedArray();
                    setComplex(normalize(attributes.getText(R.styleable.UncertainView_complex)));
                    setTarget(attributes.getResourceId(R.styleable.UncertainView_target));
                    setFraction(attributes.getFraction(R.styleable.UncertainView_fraction, 1, 1, 0));
                    Drawable wrongGuard = attributes.getDrawable(R.styleable.UncertainView_wrongGuard);
                    if (wrongGuard == null) {
                        setWrongGuard(wrongGuard);
                    }
                    Drawable nullableIcon = attributes.getDrawable(R.styleable.UncertainView_nullableIcon);
                    if (nullableIcon != null) {
                        setNullableIcon(nullableIcon);
                    }
                }

                @InspectableProperty(name = "ambiguous")
                public CharSequence getAmbiguous() {
                    return null;
                }

                public void setAmbiguous(String value) {}
                public void setAmbiguous(int value) {}
                public void setComplex(CharSequence value) {}
                public void setTarget(int value) {}
                public void setFraction(float value) {}
                public void setWrongGuard(Drawable value) {}
                public void setNullableIcon(Drawable value) {}

                private CharSequence normalize(CharSequence value) {
                    return value;
                }
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.UncertainView xmlns:app="http://schemas.android.com/apk/res-auto"
                app:ambiguous="one"
                app:complex="two"
                app:target="@id/target"
                app:fraction="@fraction/half"
                app:wrongGuard="@drawable/sample_icon"
                app:nullableIcon="@null"/>
            """.trimIndent()
        )

        val snippet = convert(layout, performer("sample.UncertainView"), ViewConversionOption.COMPATIBLE_MODE)

        assertFalse(snippet.code.contains("init ="))
        assertContains(snippet.code, "set(\"ambiguous\", \"one\")")
        assertContains(snippet.code, "set(\"complex\", \"two\")")
        assertContains(snippet.code, "set(\"target\", \"@id/target\")")
        assertContains(snippet.code, "set(\"fraction\", \"@fraction/half\")")
        assertContains(snippet.code, "set(\"wrongGuard\", \"@drawable/sample_icon\")")
        assertContains(snippet.code, "set(\"nullableIcon\", \"@null\")")
    }

    /** Verifies init-only keeps a proven mapping visible when the Performer has no init capability. */
    fun testInitOnlyPreservesProvenAttributeWhenPerformerHasNoInit() {
        installHikageTestApi()
        installInspectablePropertyApi()
        addProjectFile(
            "sample/NoInitView.java",
            """
            package sample;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class NoInitView extends View {
                @InspectableProperty(name = "title")
                public CharSequence getTitle() {
                    return null;
                }

                public void setTitle(CharSequence title) {}
            }
            """.trimIndent()
        )
        val layout = addLayoutFile(
            """
            <sample.NoInitView xmlns:app="http://schemas.android.com/apk/res-auto"
                app:title="Hello"/>
            """.trimIndent()
        )
        val parsed = requireNotNull(XmlLayoutParser.parse(layout).value)
        val resolved = XmlLayoutModelResolver.resolve(
            layout = parsed,
            facet = installAndroidSourceProvider(layout),
            declarations = listOf(performer("sample.NoInitView", init = false)),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            viewAttributeOption = ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY
        )
        val code = PerformerSnippetRenderer.render(requireNotNull(resolved.value)).code

        assertFalse(code.contains("attrs ="))
        assertFalse(code.contains("init ="))
        assertContains(code, "TODO: Convert app:title = \"Hello\" manually.")
        assertTrue(resolved.diagnostics.any { diagnostic ->
            diagnostic.kind == ConversionDiagnostic.Kind.TODO_ATTRIBUTE &&
                diagnostic.message.contains("does not support init")
        })
    }

    /** Verifies Java `isXxx` properties and gravity metadata retain valid Kotlin and IntDef symbols. */
    fun testInspectableBooleanAndGravityKeepKotlinPropertyAndSymbolicConstant() {
        installHikageTestApi()
        installInspectablePropertyMetadataApi()
        addProjectFile(
            "android/view/Gravity.java",
            """
            package android.view;

            import android.annotation.IntDef;

            public final class Gravity {
                public static final int AXIS_SPECIFIED = 1;
                public static final int CENTER_HORIZONTAL = 1;
                public static final int CENTER_VERTICAL = 16;

                @IntDef({CENTER_HORIZONTAL, CENTER_VERTICAL})
                public @interface GravityFlags {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/MetadataView.java",
            """
            package android.widget;

            import android.view.Gravity;
            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class MetadataView extends View {
                @InspectableProperty
                public boolean isChecked() {
                    return false;
                }

                public void setChecked(boolean checked) {}

                @InspectableProperty(valueType = InspectableProperty.ValueType.GRAVITY)
                public int getGravity() {
                    return Gravity.CENTER_VERTICAL;
                }

                public void setGravity(int gravity) {}
            }
            """.trimIndent()
        )
        val viewClass = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.widget.MetadataView",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
        val checked = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "checked",
            qualifiedName = "android:checked",
            rawValue = "false",
            value = "false",
            source = ConversionSource("file:///metadata.xml", TextRange.EMPTY_RANGE)
        )
        val gravity = checked.copy(
            localName = "gravity",
            qualifiedName = "android:gravity",
            rawValue = "center_vertical",
            value = "center_vertical"
        )
        val horizontalGravity = gravity.copy(
            rawValue = "center_horizontal",
            value = "center_horizontal"
        )
        val gravityDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "gravity",
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply { setValueMappings(mapOf("center_horizontal" to 1, "center_vertical" to 16)) }

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "isChecked",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.BooleanLiteral(false)
                ))
            ),
            ViewAttributePlanner.plan(checked, "android", viewClass, null, null)
        )
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "gravity",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.SymbolicConstant(
                        importName = "android.view.Gravity",
                        qualifier = "Gravity",
                        memberName = "CENTER_VERTICAL"
                    )
                ))
            ),
            ViewAttributePlanner.plan(
                gravity,
                "android",
                viewClass,
                AndroidAttributeResolver.Attribute(gravityDefinition),
                null
            )
        )
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "gravity",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.SymbolicConstant(
                        importName = "android.view.Gravity",
                        qualifier = "Gravity",
                        memberName = "CENTER_HORIZONTAL"
                    )
                ))
            ),
            ViewAttributePlanner.plan(
                horizontalGravity,
                "android",
                viewClass,
                AndroidAttributeResolver.Attribute(gravityDefinition),
                null
            )
        )
    }

    /** Verifies a source-only inspectable enum becomes its exact public enum constant without a View-specific rule. */
    fun testFrameworkScaleTypeUsesCompleteEnumValueContract() {
        val imageView = installBinaryImageViewWithSources()
        val definition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            "scaleType",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply {
            setValueMappings(mapOf(
                "matrix" to 0,
                "fitXY" to 1,
                "fitStart" to 2,
                "fitCenter" to 3,
                "fitEnd" to 4,
                "center" to 5,
                "centerCrop" to 6,
                "centerInside" to 7
            ))
        })
        val attribute = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = AndroidSymbols.NAMESPACE_URI,
            namespacePrefix = "android",
            localName = "scaleType",
            qualifiedName = "android:scaleType",
            rawValue = "centerCrop",
            value = "centerCrop",
            source = ConversionSource("file:///scale_type.xml", TextRange.EMPTY_RANGE)
        )

        val initializer = requireNotNull(ViewAttributePlanner.plan(
            attribute,
            "android",
            imageView,
            definition,
            null
        ))

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "scaleType",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.SymbolicConstant(
                        importName = "android.widget.ImageView",
                        qualifier = "ImageView.ScaleType",
                        memberName = "CENTER_CROP"
                    )
                ))
            ),
            initializer
        )
        val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.widget.ImageView",
            call = KotlinLayoutCall(
                functionName = "ImageView",
                importName = "com.highcapable.hikage.widget.android.widget.ImageView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(initializer),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals(
            """
            ImageView {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            """.trimIndent(),
            snippet.code
        )
        assertEquals(
            listOf(
                "android.widget.ImageView",
                "com.highcapable.hikage.widget.android.widget.ImageView"
            ),
            snippet.imports
        )

        val mismatchedDefinition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            "scaleType",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply {
            setValueMappings(mapOf(
                "matrix" to 0,
                "fitXY" to 1,
                "fitStart" to 2,
                "fitCenter" to 3,
                "fitEnd" to 4,
                "center" to 5,
                "centerCrop" to 60,
                "centerInside" to 7
            ))
        })
        assertNull(ViewAttributePlanner.plan(attribute, "android", imageView, mismatchedDefinition, null))
    }

    /** Verifies framework visibility uses AndroidX semantics instead of inflater enum ordinals. */
    fun testFrameworkVisibilityUsesAndroidxBooleanExtensionsInsteadOfInflaterOrdinals() {
        installHikageTestApi()
        installInspectablePropertyMetadataApi()
        addProjectFile(
            "android/widget/VisibilityView.java",
            """
            package android.widget;

            import android.view.View;
            import android.view.inspector.InspectableProperty;

            public class VisibilityView extends View {
                public static final int VISIBLE = 0;
                public static final int INVISIBLE = 4;
                public static final int GONE = 8;

                @InspectableProperty(enumMapping = {
                    @InspectableProperty.EnumEntry(value = VISIBLE, name = "visible"),
                    @InspectableProperty.EnumEntry(value = INVISIBLE, name = "invisible"),
                    @InspectableProperty.EnumEntry(value = GONE, name = "gone")
                })
                public int getVisibility() {
                    return VISIBLE;
                }

                public void setVisibility(int visibility) {}
            }
            """.trimIndent()
        )
        val viewClass = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.widget.VisibilityView",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
        val definition = AndroidAttributeResolver.Attribute(AttributeDefinition(
            ResourceNamespace.ANDROID,
            "visibility",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply { setValueMappings(mapOf("visible" to 0, "invisible" to 1, "gone" to 2)) })
        val source = ConversionSource("file:///visibility.xml", TextRange.EMPTY_RANGE)
        val expectations = mapOf(
            "visible" to Triple("isVisible", true, "androidx.core.view.isVisible"),
            "invisible" to Triple("isInvisible", true, "androidx.core.view.isInvisible"),
            "gone" to Triple("isVisible", false, "androidx.core.view.isVisible")
        )

        expectations.forEach { (xmlValue, expectation) ->
            val attribute = XmlLayoutAttribute(
                kind = XmlLayoutAttribute.Kind.VIEW,
                namespaceUri = "http://schemas.android.com/apk/res/android",
                namespacePrefix = "android",
                localName = "visibility",
                qualifiedName = "android:visibility",
                rawValue = xmlValue,
                value = xmlValue,
                source = source
            )
            val initializer = requireNotNull(ViewAttributePlanner.plan(
                attribute,
                "android",
                viewClass,
                definition,
                null
            ))
            assertEquals(
                KotlinLayoutInitializer(
                    memberName = expectation.first,
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.BooleanLiteral(expectation.second)
                    )),
                    importName = expectation.third
                ),
                initializer
            )
            val snippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
                viewClassName = "android.widget.VisibilityView",
                call = KotlinLayoutCall(
                    functionName = "VisibilityView",
                    importName = "com.highcapable.hikage.widget.android.widget.VisibilityView",
                    hasChildPerformerParameter = false
                ),
                layoutParams = null,
                id = null,
                attributes = emptyList(),
                initializers = listOf(initializer),
                todoAttributes = emptyList(),
                todoComments = emptyList(),
                children = emptyList()
            ))
            assertEquals(
                """
                VisibilityView {
                    ${expectation.first} = ${expectation.second}
                }
                """.trimIndent(),
                snippet.code
            )
            assertEquals(
                listOf(
                    expectation.third,
                    "com.highcapable.hikage.widget.android.widget.VisibilityView"
                ).sorted(),
                snippet.imports
            )
            assertFalse(snippet.code.contains("visibility ="))
            assertFalse(snippet.code.contains("= 2"))
        }
    }

    /** Verifies AndroidX resource-inspection metadata preserves inherited framework attrs and symbolic values. */
    fun testResourceInspectionAttributeUsesExactNamespaceAndInheritedSymbolicMetadata() {
        val viewClass = installBinaryResourceInspectionViewWithSources()
        val orientation = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "orientation",
            qualifiedName = "android:orientation",
            rawValue = "vertical",
            value = "vertical",
            source = ConversionSource("file:///resource_inspection.xml", TextRange.EMPTY_RANGE)
        )
        val definition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "orientation",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply { setValueMappings(mapOf("horizontal" to 0, "vertical" to 1)) }
        val gravity = orientation.copy(
            localName = "gravity",
            qualifiedName = "android:gravity",
            rawValue = "center|start",
            value = "center|start"
        )
        val gravityDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "gravity",
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply { setValueMappings(mapOf("center" to 17, "start" to 8_388_611)) }

        assertNoErrorLogged {
            assertEquals(
                KotlinLayoutInitializer(
                    memberName = "orientation",
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.SymbolicConstant(
                            importName = "androidx.appcompat.widget.LinearLayoutCompat",
                            qualifier = "LinearLayoutCompat",
                            memberName = "VERTICAL"
                        )
                    ))
                ),
                ViewAttributePlanner.plan(
                    orientation,
                    "android",
                    viewClass,
                    AndroidAttributeResolver.Attribute(definition),
                    null
                )
            )
            assertEquals(
                KotlinLayoutInitializer(
                    memberName = "gravity",
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.BitwiseOr(listOf(
                            KotlinLayoutInitializer.Value.SymbolicConstant(
                                importName = "android.view.Gravity",
                                qualifier = "Gravity",
                                memberName = "CENTER"
                            ),
                            KotlinLayoutInitializer.Value.SymbolicConstant(
                                importName = "android.view.Gravity",
                                qualifier = "Gravity",
                                memberName = "START"
                            )
                        ))
                    ))
                ),
                ViewAttributePlanner.plan(
                    gravity,
                    "android",
                    viewClass,
                    AndroidAttributeResolver.Attribute(gravityDefinition),
                    null
                )
            )
            assertEquals(
                KotlinLayoutInitializer(
                    memberName = "gravity",
                    memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                    arguments = listOf(KotlinLayoutInitializer.Argument(
                        value = KotlinLayoutInitializer.Value.SymbolicConstant(
                            importName = "android.view.Gravity",
                            qualifier = "Gravity",
                            memberName = "CENTER"
                        )
                    ))
                ),
                ViewAttributePlanner.plan(
                    gravity.copy(rawValue = "center", value = "center"),
                    "android",
                    viewClass,
                    AndroidAttributeResolver.Attribute(gravityDefinition),
                    null
                )
            )
            assertNull(ViewAttributePlanner.plan(
                orientation,
                "android",
                viewClass,
                null,
                null
            ))
            assertNull(ViewAttributePlanner.plan(
                orientation.copy(
                    namespaceUri = "http://schemas.android.com/apk/res-auto",
                    namespacePrefix = "app",
                    qualifiedName = "app:orientation"
                ),
                "app",
                viewClass,
                AndroidAttributeResolver.Attribute(definition),
                null
            ))
        }
    }

    /** Verifies gravity metadata remains usable when its inspection enum and IntDef exist only in attached SDK sources. */
    fun testGravityUsesAttachedSourceWhenBinaryOmitsInspectionMetadata() {
        val viewClass = installBinaryGravityViewWithSources()
        val gravity = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "gravity",
            qualifiedName = "android:gravity",
            rawValue = "center",
            value = "center",
            source = ConversionSource("file:///gravity.xml", TextRange.EMPTY_RANGE)
        )
        val gravityDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "gravity",
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply { setValueMappings(mapOf("center" to 17)) }

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "gravity",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.SymbolicConstant(
                        importName = "android.view.Gravity",
                        qualifier = "Gravity",
                        memberName = "CENTER"
                    )
                ))
            ),
            ViewAttributePlanner.plan(
                gravity,
                "android",
                viewClass,
                AndroidAttributeResolver.Attribute(gravityDefinition),
                null
            )
        )
    }

    /** Verifies inspectable flag expressions and source-proven TypedArray delegates keep symbolic Material writes. */
    fun testMaterialAttributesUseSymbolicFlagsAndProvenTypedArrayDelegate() {
        installInspectablePropertyMetadataApi()
        installTypedArrayApi()
        addProjectFile(
            "android/text/InputType.java",
            """
            package android.text;

            public final class InputType {
                public static final int TYPE_CLASS_TEXT = 1;
                public static final int TYPE_TEXT_VARIATION_PASSWORD = 128;
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/InputView.java",
            """
            package android.widget;

            import android.text.InputType;
            import android.view.inspector.InspectableProperty;

            public class InputView {
                @InspectableProperty(flagMapping = {
                    @InspectableProperty.FlagEntry(
                        name = "textPassword",
                        target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    )
                })
                public int getInputType() {
                    return 0;
                }

                public void setInputType(int inputType) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "androidx/appcompat/widget/DelegatingTypedArray.java",
            """
            package androidx.appcompat.widget;

            import android.content.res.TypedArray;

            public class DelegatingTypedArray {
                private final TypedArray wrapped = new TypedArray();

                public CharSequence getText(int index) {
                    return wrapped.getText(index);
                }

                public int getInt(int index, int defaultValue) {
                    return wrapped.getInt(index, defaultValue);
                }

                public boolean getBoolean(int index, boolean defaultValue) {
                    return wrapped.getBoolean(index, defaultValue);
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "androidx/appcompat/widget/TransformingTypedArray.java",
            """
            package androidx.appcompat.widget;

            import android.content.res.TypedArray;

            public class TransformingTypedArray {
                private final TypedArray wrapped = new TypedArray();

                public int getInt(int index, int defaultValue) {
                    return wrapped.getInt(index, defaultValue) + 1;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/google/android/material/R.java",
            """
            package com.google.android.material;

            public final class R {
                public static final class styleable {
                    public static final int TextInputLayout_android_hint = 0;
                    public static final int TextInputLayout_endIconMode = 1;
                    public static final int ChipGroup_singleSelection = 2;
                    public static final int UnsafeInputLayout_endIconMode = 3;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/google/android/material/textfield/UnsafeInputLayout.java",
            """
            package com.google.android.material.textfield;

            import androidx.appcompat.widget.TransformingTypedArray;
            import com.google.android.material.R;

            public class UnsafeInputLayout {
                public UnsafeInputLayout() {
                    TransformingTypedArray attributes = new TransformingTypedArray();
                    setEndIconMode(attributes.getInt(R.styleable.UnsafeInputLayout_endIconMode, 0));
                }

                public void setEndIconMode(int endIconMode) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/google/android/material/textfield/TextInputLayout.java",
            """
            package com.google.android.material.textfield;

            import android.annotation.IntDef;
            import androidx.appcompat.widget.DelegatingTypedArray;
            import com.google.android.material.R;

            public class TextInputLayout {
                public static final int END_ICON_NONE = 0;
                public static final int END_ICON_PASSWORD_TOGGLE = 1;

                @IntDef({END_ICON_NONE, END_ICON_PASSWORD_TOGGLE})
                public @interface EndIconMode {}

                public TextInputLayout() {
                    DelegatingTypedArray attributes = new DelegatingTypedArray();
                    setHint(attributes.getText(R.styleable.TextInputLayout_android_hint));
                    setEndIconMode(attributes.getInt(R.styleable.TextInputLayout_endIconMode, END_ICON_NONE));
                }

                public void setHint(CharSequence hint) {}

                public void setEndIconMode(@EndIconMode int endIconMode) {}
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/google/android/material/chip/ChipGroup.java",
            """
            package com.google.android.material.chip;

            import androidx.appcompat.widget.DelegatingTypedArray;
            import com.google.android.material.R;

            public class ChipGroup {
                private final SelectionController controller = new SelectionController();

                public ChipGroup() {
                    DelegatingTypedArray attributes = new DelegatingTypedArray();
                    controller.setSingleSelection(
                        attributes.getBoolean(R.styleable.ChipGroup_singleSelection, false)
                    );
                }

                public void setSingleSelection(boolean singleSelection) {
                    controller.setSingleSelection(singleSelection);
                }

                private static final class SelectionController {
                    void setSingleSelection(boolean singleSelection) {}
                }
            }
            """.trimIndent()
        )
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        val inputView = requireNotNull(JavaPsiFacade.getInstance(project).findClass("android.widget.InputView", scope))
        val textInputLayout = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.google.android.material.textfield.TextInputLayout",
            scope
        ))
        val chipGroup = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.google.android.material.chip.ChipGroup",
            scope
        ))
        val unsafeInputLayout = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.google.android.material.textfield.UnsafeInputLayout",
            scope
        ))
        val source = ConversionSource("file:///material.xml", TextRange.EMPTY_RANGE)
        val inputType = XmlLayoutAttribute(
            kind = XmlLayoutAttribute.Kind.VIEW,
            namespaceUri = "http://schemas.android.com/apk/res/android",
            namespacePrefix = "android",
            localName = "inputType",
            qualifiedName = "android:inputType",
            rawValue = "textPassword",
            value = "textPassword",
            source = source
        )
        val hint = inputType.copy(
            localName = "hint",
            qualifiedName = "android:hint",
            rawValue = "@string/text_username",
            value = "@string/text_username"
        )
        val endIconMode = inputType.copy(
            namespaceUri = "http://schemas.android.com/apk/res-auto",
            namespacePrefix = "app",
            localName = "endIconMode",
            qualifiedName = "app:endIconMode",
            rawValue = "password_toggle",
            value = "password_toggle"
        )
        val singleSelection = endIconMode.copy(
            localName = "singleSelection",
            qualifiedName = "app:singleSelection",
            rawValue = "true",
            value = "true"
        )
        val inputTypeDefinition = AttributeDefinition(
            ResourceNamespace.ANDROID,
            "inputType",
            null,
            setOf(AttributeFormat.FLAGS)
        ).apply { setValueMappings(mapOf("textPassword" to 129)) }
        val endIconModeDefinition = AttributeDefinition(
            ResourceNamespace.RES_AUTO,
            "endIconMode",
            null,
            setOf(AttributeFormat.ENUM)
        ).apply { setValueMappings(mapOf("password_toggle" to 1)) }

        assertEquals(
            KotlinLayoutInitializer(
                memberName = "inputType",
                memberKind = KotlinLayoutInitializer.MemberKind.PROPERTY,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.BitwiseOr(listOf(
                        KotlinLayoutInitializer.Value.SymbolicConstant(
                            importName = "android.text.InputType",
                            qualifier = "InputType",
                            memberName = "TYPE_CLASS_TEXT"
                        ),
                        KotlinLayoutInitializer.Value.SymbolicConstant(
                            importName = "android.text.InputType",
                            qualifier = "InputType",
                            memberName = "TYPE_TEXT_VARIATION_PASSWORD"
                        )
                    ))
                ))
            ),
            ViewAttributePlanner.plan(
                inputType,
                "android",
                inputView,
                AndroidAttributeResolver.Attribute(inputTypeDefinition),
                null
            )
        )
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setHint",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.Resource(
                        resourceClassName = "com.highcapable.hikage.fixture.R",
                        resourceType = "string",
                        resourceName = "text_username",
                        helperName = "textResource"
                    )
                ))
            ),
            ViewAttributePlanner.plan(
                hint,
                "android",
                textInputLayout,
                null,
                "com.highcapable.hikage.fixture"
            )
        )
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setEndIconMode",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.SymbolicConstant(
                        importName = "com.google.android.material.textfield.TextInputLayout",
                        qualifier = "TextInputLayout",
                        memberName = "END_ICON_PASSWORD_TOGGLE"
                    )
                ))
            ),
            ViewAttributePlanner.plan(
                endIconMode,
                "app",
                textInputLayout,
                AndroidAttributeResolver.Attribute(endIconModeDefinition),
                null
            )
        )
        assertEquals(
            KotlinLayoutInitializer(
                memberName = "setSingleSelection",
                memberKind = KotlinLayoutInitializer.MemberKind.METHOD,
                arguments = listOf(KotlinLayoutInitializer.Argument(
                    value = KotlinLayoutInitializer.Value.BooleanLiteral(true)
                ))
            ),
            ViewAttributePlanner.plan(
                singleSelection,
                "app",
                chipGroup,
                null,
                null
            )
        )
        assertNull(ViewAttributePlanner.plan(
            endIconMode,
            "app",
            unsafeInputLayout,
            AndroidAttributeResolver.Attribute(endIconModeDefinition),
            null
        ))
        val inputTypeInitializer = requireNotNull(ViewAttributePlanner.plan(
            inputType,
            "android",
            inputView,
            AndroidAttributeResolver.Attribute(inputTypeDefinition),
            null
        ))
        val inputTypeSnippet = PerformerSnippetRenderer.render(KotlinLayoutNode(
            viewClassName = "android.widget.InputView",
            call = KotlinLayoutCall(
                functionName = "InputView",
                importName = "com.highcapable.hikage.widget.android.widget.InputView",
                hasChildPerformerParameter = false
            ),
            layoutParams = null,
            id = null,
            attributes = emptyList(),
            initializers = listOf(inputTypeInitializer),
            todoAttributes = emptyList(),
            todoComments = emptyList(),
            children = emptyList()
        ))
        assertEquals(
            """
            InputView {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            """.trimIndent(),
            inputTypeSnippet.code
        )
        assertTrue(inputTypeSnippet.imports.contains("android.text.InputType"))
        assertFalse(inputTypeSnippet.code.contains("129"))
    }

    private fun convert(
        layout: XmlFile,
        performer: PerformerDeclaration,
        option: ViewConversionOption
    ) = XmlLayoutModelResolver.resolve(
        layout = requireNotNull(XmlLayoutParser.parse(layout).value),
        facet = installAndroidSourceProvider(layout),
        declarations = listOf(performer),
        rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
        resourcePackageName = "sample",
        viewAttributeOption = option
    ).let { outcome -> PerformerSnippetRenderer.render(requireNotNull(outcome.value)) }

    private fun addLayoutFile(source: String) =
        addProjectFile("app/src/main/res/layout/init_test.xml", source) as XmlFile

    private fun installAndroidSourceProvider(layout: PsiFile): AndroidFacet {
        val manifest = addProjectFile("app/src/main/AndroidManifest.xml", "<manifest/>")
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

    private fun installInspectablePropertyApi() {
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
    }

    private fun installInspectablePropertyMetadataApi() {
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
                FlagEntry[] flagMapping() default {};

                @interface FlagEntry {
                    String name();
                    int target();
                    int mask() default 0;
                }

                enum ValueType {
                    NONE,
                    GRAVITY
                }
            }
            """.trimIndent()
        )
    }

    private fun installTypedArrayApi() {
        addProjectFile(
            "android/content/res/TypedArray.java",
            """
            package android.content.res;

            import android.graphics.drawable.Drawable;

            public class TypedArray {
                public CharSequence getText(int index) {
                    return null;
                }

                public int getInt(int index, int defaultValue) {
                    return 0;
                }

                public boolean getBoolean(int index, boolean defaultValue) {
                    return false;
                }

                public Drawable getDrawable(int index) {
                    return null;
                }

                public int getDimensionPixelOffset(int index) {
                    return 0;
                }

                public int getResourceId(int index) {
                    return 0;
                }

                public float getFraction(int index, int base, int parentBase, float defaultValue) {
                    return 0;
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/graphics/drawable/Drawable.java",
            """
            package android.graphics.drawable;

            public class Drawable {}
            """.trimIndent()
        )
    }

    private fun installBinaryImageViewWithSources(): PsiClass {
        val libraryRoot = FileUtil.createTempDirectory("hikage-image-view", "", true)
        val sourceRoot = libraryRoot.resolve("src").apply(File::mkdirs)
        val classesRoot = libraryRoot.resolve("classes").apply(File::mkdirs)
        val sources = mapOf(
            "android/annotation/DrawableRes.java" to
                """
                package android.annotation;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
                public @interface DrawableRes {}
                """.trimIndent(),
            "android/view/inspector/InspectableProperty.java" to
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
                """.trimIndent(),
            "android/view/View.java" to
                """
                package android.view;

                public class View {}
                """.trimIndent(),
            "android/widget/ImageView.java" to
                """
                package android.widget;

                import android.annotation.DrawableRes;
                import android.view.View;
                import android.view.inspector.InspectableProperty;

                public class ImageView extends View {
                    /** @attr ref android.R.styleable#ImageView_src */
                    public void setImageResource(@DrawableRes int resourceId) {}

                    public enum ScaleType {
                        MATRIX(0),
                        FIT_XY(1),
                        FIT_START(2),
                        FIT_CENTER(3),
                        FIT_END(4),
                        CENTER(5),
                        CENTER_CROP(6),
                        CENTER_INSIDE(7);

                        ScaleType(int nativeInt) {}
                    }

                    public void setScaleType(ScaleType scaleType) {}

                    @InspectableProperty
                    public ScaleType getScaleType() {
                        return ScaleType.FIT_CENTER;
                    }
                }
                """.trimIndent()
        )
        val sourceFiles = sources.map { (path, source) ->
            sourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        val arguments = listOf("-d", classesRoot.path) + sourceFiles.map(File::getPath)
        val compilation = ProcessBuilder(listOf("javac") + arguments)
            .redirectErrorStream(true)
            .start()
        val compilationOutput = compilation.inputStream.bufferedReader().use { reader -> reader.readText() }
        assertEquals(compilationOutput, 0, compilation.waitFor())
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "android-image-view-with-sources",
            listOf(VfsUtilCore.pathToUrl(classesRoot.path)),
            listOf(VfsUtilCore.pathToUrl(sourceRoot.path))
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.widget.ImageView",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
    }

    private fun installBinaryTextViewWithSourceOnlyStyle(): PsiClass {
        val libraryRoot = FileUtil.createTempDirectory("hikage-text-style-view", "", true)
        val compiledSourceRoot = libraryRoot.resolve("compiled-src").apply(File::mkdirs)
        val sourceRoot = libraryRoot.resolve("src").apply(File::mkdirs)
        val classesRoot = libraryRoot.resolve("classes").apply(File::mkdirs)
        val sharedSources = mapOf(
            "android/annotation/IntDef.java" to
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
                """.trimIndent(),
            "android/view/View.java" to
                """
                package android.view;

                public class View {}
                """.trimIndent()
        )
        val compiledSources = sharedSources + mapOf(
            "android/graphics/Typeface.java" to
                """
                package android.graphics;

                public class Typeface {
                    public static final int NORMAL = 0;
                    public static final int BOLD = 1;
                    public static final int ITALIC = 2;
                    public static final int BOLD_ITALIC = 3;
                }
                """.trimIndent(),
            "android/widget/TextView.java" to
                """
                package android.widget;

                import android.graphics.Typeface;
                import android.view.View;

                public class TextView extends View {
                    public Typeface getTypeface() {
                        return null;
                    }

                    public void setTypeface(Typeface typeface) {}

                    public void setTypeface(Typeface typeface, int style) {}
                }
                """.trimIndent()
        )
        val attachedSources = sharedSources + mapOf(
            "android/graphics/Typeface.java" to
                """
                package android.graphics;

                import android.annotation.IntDef;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class Typeface {
                    public static final int NORMAL = 0;
                    public static final int BOLD = 1;
                    public static final int ITALIC = 2;
                    public static final int BOLD_ITALIC = 3;

                    @IntDef(value = {NORMAL, BOLD, ITALIC, BOLD_ITALIC}, flag = true)
                    @Retention(RetentionPolicy.SOURCE)
                    public @interface Style {}
                }
                """.trimIndent(),
            "android/widget/TextView.java" to
                """
                package android.widget;

                import android.graphics.Typeface;
                import android.view.View;

                public class TextView extends View {
                    public Typeface getTypeface() {
                        return null;
                    }

                    public void setTypeface(Typeface typeface) {}

                    /**
                     * @attr ref android.R.styleable#TextView_typeface
                     * @attr ref android.R.styleable#TextView_textStyle
                     */
                    public void setTypeface(Typeface typeface, @Typeface.Style int style) {}
                }
                """.trimIndent()
        )
        val sourceFiles = compiledSources.map { (path, source) ->
            compiledSourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        attachedSources.forEach { (path, source) ->
            sourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        val arguments = listOf("-d", classesRoot.path) + sourceFiles.map(File::getPath)
        val compilation = ProcessBuilder(listOf("javac") + arguments)
            .redirectErrorStream(true)
            .start()
        val compilationOutput = compilation.inputStream.bufferedReader().use { reader -> reader.readText() }
        assertEquals(compilationOutput, 0, compilation.waitFor())
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "android-text-style-view-with-sources",
            listOf(VfsUtilCore.pathToUrl(classesRoot.path)),
            listOf(VfsUtilCore.pathToUrl(sourceRoot.path))
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.widget.TextView",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
    }

    private fun installBinaryResourceInspectionViewWithSources(): PsiClass {
        val libraryRoot = FileUtil.createTempDirectory("hikage-resource-inspection", "", true)
        val sourceRoot = libraryRoot.resolve("src").apply(File::mkdirs)
        val classesRoot = libraryRoot.resolve("classes").apply(File::mkdirs)
        val sources = mapOf(
            "android/annotation/IntDef.java" to
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
                """.trimIndent(),
            "android/view/View.java" to
                """
                package android.view;

                public class View {}
                """.trimIndent(),
            "android/view/ViewGroup.java" to
                """
                package android.view;

                public class ViewGroup extends View {}
                """.trimIndent(),
            "android/view/Gravity.java" to
                """
                package android.view;

                import android.annotation.IntDef;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public final class Gravity {
                    public static final int CENTER_HORIZONTAL = 1;
                    public static final int CENTER_VERTICAL = 16;
                    public static final int CENTER = 17;
                    public static final int START = 8388611;

                    @IntDef(value = {CENTER_HORIZONTAL, CENTER_VERTICAL, CENTER, START}, flag = true)
                    @Retention(RetentionPolicy.SOURCE)
                    public @interface GravityFlags {}
                }
                """.trimIndent(),
            "androidx/annotation/GravityInt.java" to
                """
                package androidx.annotation;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.METHOD, ElementType.PARAMETER})
                public @interface GravityInt {}
                """.trimIndent(),
            "androidx/resourceinspection/annotation/Attribute.java" to
                """
                package androidx.resourceinspection.annotation;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.METHOD)
                public @interface Attribute {
                    String value();
                    IntMap[] intMapping() default {};

                    @interface IntMap {
                        int value();
                        String name();
                    }
                }
                """.trimIndent(),
            "androidx/appcompat/widget/LinearLayoutCompat.java" to
                """
                package androidx.appcompat.widget;

                import android.annotation.IntDef;
                import android.view.Gravity;
                import android.view.ViewGroup;
                import androidx.annotation.GravityInt;
                import androidx.resourceinspection.annotation.Attribute;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class LinearLayoutCompat extends ViewGroup {
                    public static final int HORIZONTAL = 0;
                    public static final int VERTICAL = 1;

                    @IntDef({HORIZONTAL, VERTICAL})
                    @Retention(RetentionPolicy.SOURCE)
                    public @interface OrientationMode {}

                    @Attribute(value = "android:orientation", intMapping = {
                        @Attribute.IntMap(value = HORIZONTAL, name = "horizontal"),
                        @Attribute.IntMap(value = VERTICAL, name = "vertical")
                    })
                    @OrientationMode
                    public int getOrientation() {
                        return HORIZONTAL;
                    }

                    public void setOrientation(@OrientationMode int orientation) {}

                    @Attribute("android:gravity")
                    @GravityInt
                    public int getGravity() {
                        return Gravity.CENTER;
                    }

                    public void setGravity(@GravityInt int gravity) {}
                }
                """.trimIndent(),
            "com/highcapable/hikage/fixture/ResourceInspectionRoot.java" to
                """
                package com.highcapable.hikage.fixture;

                import androidx.appcompat.widget.LinearLayoutCompat;

                public class ResourceInspectionRoot extends LinearLayoutCompat {}
                """.trimIndent()
        )
        val sourceFiles = sources.map { (path, source) ->
            sourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        val arguments = listOf("-d", classesRoot.path) + sourceFiles.map(File::getPath)
        val compilation = ProcessBuilder(listOf("javac") + arguments)
            .redirectErrorStream(true)
            .start()
        val compilationOutput = compilation.inputStream.bufferedReader().use { reader -> reader.readText() }
        assertEquals(compilationOutput, 0, compilation.waitFor())
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "hikage-resource-inspection-with-sources",
            listOf(VfsUtilCore.pathToUrl(classesRoot.path)),
            listOf(VfsUtilCore.pathToUrl(sourceRoot.path))
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "com.highcapable.hikage.fixture.ResourceInspectionRoot",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
    }

    private fun installBinaryGravityViewWithSources(): PsiClass {
        val libraryRoot = FileUtil.createTempDirectory("hikage-gravity-view", "", true)
        val compiledSourceRoot = libraryRoot.resolve("compiled-src").apply(File::mkdirs)
        val sourceRoot = libraryRoot.resolve("src").apply(File::mkdirs)
        val classesRoot = libraryRoot.resolve("classes").apply(File::mkdirs)
        val sharedSources = mapOf(
            "android/annotation/IntDef.java" to
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
                }
                """.trimIndent(),
            "android/view/inspector/InspectableProperty.java" to
                """
                package android.view.inspector;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.SOURCE)
                @Target(ElementType.METHOD)
                public @interface InspectableProperty {
                    ValueType valueType() default ValueType.NONE;

                    enum ValueType {
                        NONE,
                        GRAVITY
                    }
                }
                """.trimIndent(),
            "android/view/View.java" to
                """
                package android.view;

                public class View {}
                """.trimIndent(),
            "android/widget/MetadataView.java" to
                """
                package android.widget;

                import android.view.Gravity;
                import android.view.View;
                import android.view.inspector.InspectableProperty;

                public class MetadataView extends View {
                    @InspectableProperty(valueType = InspectableProperty.ValueType.GRAVITY)
                    public int getGravity() {
                        return Gravity.CENTER;
                    }

                    public void setGravity(int gravity) {}
                }
                """.trimIndent()
        )
        val compiledSources = sharedSources + ("android/view/Gravity.java" to
            """
            package android.view;

            public final class Gravity {
                public static final int CENTER = 17;
            }
            """.trimIndent())
        val attachedSources = sharedSources + ("android/view/Gravity.java" to
            """
            package android.view;

            import android.annotation.IntDef;

            public final class Gravity {
                public static final int CENTER = 17;

                @IntDef({CENTER})
                public @interface GravityFlags {}
            }
            """.trimIndent())
        val sourceFiles = compiledSources.map { (path, source) ->
            compiledSourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        attachedSources.forEach { (path, source) ->
            sourceRoot.resolve(path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        val arguments = listOf("-d", classesRoot.path) + sourceFiles.map(File::getPath)
        val compilation = ProcessBuilder(listOf("javac") + arguments)
            .redirectErrorStream(true)
            .start()
        val compilationOutput = compilation.inputStream.bufferedReader().use { reader -> reader.readText() }
        assertEquals(compilationOutput, 0, compilation.waitFor())
        assertTrue(classesRoot.resolve("android/view/inspector/InspectableProperty.class").delete())
        assertTrue(classesRoot.resolve($$"android/view/inspector/InspectableProperty$ValueType.class").delete())
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "android-gravity-view-with-sources",
            listOf(VfsUtilCore.pathToUrl(classesRoot.path)),
            listOf(VfsUtilCore.pathToUrl(sourceRoot.path))
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "android.widget.MetadataView",
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ))
    }

    private fun performer(
        viewClass: String,
        init: Boolean = true,
        isViewGroup: Boolean = false
    ): PerformerDeclaration {
        val declaration = requireNotNull(ViewDeclaration.from(viewClass, alias = null, isViewGroup = isViewGroup))
        return PerformerDeclaration(
            spec = PerformerSpec(
                lparams = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS.takeIf { isViewGroup },
                attrs = true,
                init = init,
                performer = isViewGroup
            ),
            declaration = declaration,
            source = PerformerDeclaration.Source.OPTIONAL_FILE
        )
    }
}