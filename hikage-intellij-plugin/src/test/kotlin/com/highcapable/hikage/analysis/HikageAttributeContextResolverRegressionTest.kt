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
 * This file is created by fankes on 2026/7/27.
 */
package com.highcapable.hikage.analysis

import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.ScopeType
import com.android.tools.idea.projectsystem.SourceProviders
import com.highcapable.hikage.inspection.HikageAttributeInspection
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.analysis.api.permissions.forbidAnalysis
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Verifies app-namespace and parent-layout resolution across Hikage attribute consumers.
 */
class HikageAttributeContextResolverRegressionTest : HikageCodeInsightTestCase() {

    private var createdFacet: AndroidFacet? = null

    /** Verifies ordinary calls stop before the resolver enters Kotlin Analysis. */
    fun testOrdinaryCallSkipsAttributeResolutionWithoutAnalysis() {
        installHikageTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "OrdinaryAttributeCall.kt",
            """
            package sample

            fun ordinary(value: String) = Unit

            fun verify() {
                ordinary("unrelated")
            }
            """.trimIndent()
        )
        val expression = file.collectDescendantsOfType<KtCallExpression>()
            .single { candidate -> candidate.calleeExpression?.text == "ordinary" }

        val setCall = forbidAnalysis("ordinary attribute call resolution") {
            HikageAttributeContextResolver.from(project).resolveSetCall(expression)
        }

        assertNull(setCall)
    }

    /** Verifies concurrent editor consumers reuse one confirmed setter resolution for the same PSI state. */
    fun testConfirmedSetterResolutionIsReusedWithoutAnalysis() {
        installHikageTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "CachedAttributeSet.kt",
            """
            package sample

            import com.highcapable.hikage.core.attribute.HikageAttribute

            fun verify() = HikageAttribute {
                set("android:text", "Hello")
            }
            """.trimIndent()
        )
        val expression = file.collectDescendantsOfType<KtCallExpression>()
            .single { candidate -> candidate.calleeExpression?.text == "set" }

        assertNotNull(HikageAttributeContextResolver.from(project).resolveSetCall(expression))
        val setCall = forbidAnalysis("cached Hikage attribute setter resolution") {
            HikageAttributeContextResolver.from(project).resolveSetCall(expression)
        }

        assertNotNull(setCall)
    }

    /** Verifies parent `app` layout attrs resolve for navigation and do not produce an unknown-attr diagnostic. */
    fun testParentAppLayoutAttributeResolvesFromChildAttrsBlock() {
        installScopedHikageApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        installAndroidResources()
        myFixture.enableInspections(HikageAttributeInspection.UnknownHikageAttribute())
        val file = configureKotlinByText(
            "ConstraintLayoutUsage.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.app
            import com.highcapable.hikage.widget.android.widget.Button
            import com.highcapable.hikage.widget.androidx.constraintlayout.widget.ConstraintLayout

            fun verify() = Hikage.create {
                ConstraintLayout {
                    Button(attrs = {
                        app {
                            set("layout_constraintBottom_toTopOf", "parent")
                        }
                    })
                }
            }
            """.trimIndent()
        )
        val expression = file.collectDescendantsOfType<KtStringTemplateExpression>()
            .first { candidate -> candidate.text.contains("layout_constraintBottom_toTopOf") }

        val setCall = computeInBackgroundReadAction {
            HikageAttributeContextResolver.from(project).resolveSetCall(expression)
        }
        assertNotNull("The Hikage set call must resolve.", setCall)
        val scopes = computeInBackgroundReadAction {
            setCall?.let(HikageAttributeContextResolver.from(project)::resolveScopes)
        }
        assertNotNull("The child attrs block must expose its View and parent layout scopes.", scopes)
        assertEquals(
            "androidx.constraintlayout.widget.ConstraintLayout",
            scopes?.layout?.parentViewClasses?.singleOrNull()?.qualifiedName
        )
        val reference = computeInBackgroundReadAction {
            HikageAttributeContextResolver.from(project).resolveReference(expression)
        }

        assertNotNull("The parent layout attribute must resolve.", reference)
        assertEquals("layout_constraintBottom_toTopOf", reference?.name)
        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        assertFalse(descriptions.any { description -> description.contains("Cannot resolve attribute") })
    }

    /** Verifies a written `app` attr remains valid without broadening View-scoped completion. */
    fun testAppAttributeOutsideViewStyleableRemainsGloballyResolvable() {
        installScopedHikageApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        installAndroidResources()
        myFixture.enableInspections(HikageAttributeInspection.UnknownHikageAttribute())
        val file = configureKotlinByText(
            "FragmentContainerViewUsage.kt",
            """
            package sample

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.app
            import com.highcapable.hikage.widget.androidx.fragment.app.FragmentContainerView

            fun verify() = Hikage.create {
                FragmentContainerView(attrs = {
                    app {
                        set("defaultNavHost", true)
                    }
                })
            }
            """.trimIndent()
        )
        val expression = file.collectDescendantsOfType<KtStringTemplateExpression>()
            .first { candidate -> candidate.text.contains("defaultNavHost") }
        val setCall = computeInBackgroundReadAction {
            HikageAttributeContextResolver.from(project).resolveSetCall(expression)
        }
        assertNotNull("The Hikage set call must resolve.", setCall)
        val scopes = computeInBackgroundReadAction {
            setCall?.let(HikageAttributeContextResolver.from(project)::resolveScopes)
        }
        assertNotNull("The FragmentContainerView attrs block must expose its View scope.", scopes?.view)
        val completionNames = computeInBackgroundReadAction {
            AndroidAttributeResolver.from(expression)
                ?.attributes("app", scopes?.view, scopes?.layout)
                ?.map(AndroidAttributeResolver.Attribute::name)
                .orEmpty()
        }
        val reference = computeInBackgroundReadAction {
            HikageAttributeContextResolver.from(project).resolveReference(expression)
        }

        assertFalse("Another styleable's attr must not enter View-scoped completion.", "defaultNavHost" in completionNames)
        assertNotNull("The globally defined app attribute must resolve.", reference)
        assertEquals("defaultNavHost", reference?.name)
        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }
        assertFalse(descriptions.any { description -> description.contains("Cannot resolve attribute") })
    }

    private fun installScopedHikageApi() {
        addProjectFile(
            "android/view/View.kt",
            """
            package android.view

            open class View
            """.trimIndent()
        )
        addProjectFile(
            "android/view/ViewGroup.kt",
            """
            package android.view

            open class ViewGroup : View() {
                open class LayoutParams
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/Button.kt",
            """
            package android.widget

            import android.view.View

            open class Button : View()
            """.trimIndent()
        )
        addProjectFile(
            "androidx/constraintlayout/widget/ConstraintLayout.kt",
            """
            package androidx.constraintlayout.widget

            import android.view.ViewGroup

            open class ConstraintLayout : ViewGroup() {
                class LayoutParams : ViewGroup.LayoutParams()
            }
            """.trimIndent()
        )
        addProjectFile(
            "androidx/fragment/app/FragmentContainerView.kt",
            """
            package androidx.fragment.app

            import android.view.ViewGroup

            open class FragmentContainerView : ViewGroup()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/annotation/Hikagable.kt",
            """
            package com.highcapable.hikage.annotation

            @Target(AnnotationTarget.FUNCTION)
            annotation class Hikagable
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

                companion object {
                    fun create(performer: Performer<ViewGroup.LayoutParams>.() -> Unit) = Hikage()
                }
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/attribute/HikageAttributeNamespaceUtils.kt",
            """
            package com.highcapable.hikage.core.attribute

            import com.highcapable.hikage.core.Hikage

            inline fun Hikage.Attribute.app(block: AttributeScope.() -> Unit) = AttributeScope().block()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/attribute/HikageAttributeUtils.kt",
            """
            package com.highcapable.hikage.core.attribute

            class AttributeScope {
                fun set(name: String, value: Any? = null) = Unit
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/widget/androidx/constraintlayout/widget/ConstraintLayout.kt",
            """
            package com.highcapable.hikage.widget.androidx.constraintlayout.widget

            import android.view.ViewGroup
            import androidx.constraintlayout.widget.ConstraintLayout
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            @Hikagable
            fun <LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.ConstraintLayout(
                attrs: Hikage.Attribute.() -> Unit = {},
                performer: Hikage.Performer<ConstraintLayout.LayoutParams>.() -> Unit = {}
            ): ConstraintLayout = ConstraintLayout()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/widget/androidx/fragment/app/FragmentContainerView.kt",
            """
            package com.highcapable.hikage.widget.androidx.fragment.app

            import android.view.ViewGroup
            import androidx.fragment.app.FragmentContainerView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            @Hikagable
            fun <LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.FragmentContainerView(
                attrs: Hikage.Attribute.() -> Unit = {}
            ): FragmentContainerView = FragmentContainerView()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/widget/android/widget/Button.kt",
            """
            package com.highcapable.hikage.widget.android.widget

            import android.view.ViewGroup
            import android.widget.Button
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage

            @Hikagable
            fun <LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.Button(
                attrs: Hikage.Attribute.() -> Unit = {}
            ): Button = Button()
            """.trimIndent()
        )
    }

    private fun installAndroidResources() {
        val manifest = addProjectFile("app/src/main/AndroidManifest.xml", "<manifest/>")
        val attrs = addProjectFile(
            "app/src/main/res/values/attrs.xml",
            """
            <resources xmlns:constraint="http://schemas.android.com/apk/res/androidx.constraintlayout.widget"
                xmlns:navigation="http://schemas.android.com/apk/res/androidx.navigation.fragment">
                <declare-styleable name="ConstraintLayout_Layout">
                    <attr name="constraint:layout_constraintBottom_toTopOf"/>
                </declare-styleable>
                <declare-styleable name="NavHostFragment">
                    <attr name="navigation:defaultNavHost" format="boolean"/>
                </declare-styleable>
            </resources>
            """.trimIndent()
        )
        val facet = AndroidFacet.getInstance(module) ?: WriteAction.compute<AndroidFacet, RuntimeException> {
            FacetManager.getInstance(module).addFacet(AndroidFacet.getFacetType(), AndroidFacet.NAME, null)
        }.also { createdFacet = it }
        val sourceProvider = NamedIdeaSourceProviderBuilder.create("main", manifest.virtualFile.url)
            .withScopeType(ScopeType.MAIN)
            .withResDirectoryUrls(listOf(requireNotNull(attrs.virtualFile.parent?.parent).url))
            .build()
        SourceProviders.replaceForTest(facet, testRootDisposable, sourceProvider)
    }

    override fun tearDown() {
        try {
            createdFacet?.let { facet ->
                WriteAction.compute<Unit, RuntimeException> {
                    val model = FacetManager.getInstance(module).createModifiableModel()
                    model.removeFacet(facet)
                    model.commit()
                }
            }
        } finally {
            createdFacet = null
            super.tearDown()
        }
    }
}