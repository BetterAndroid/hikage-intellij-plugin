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
package com.highcapable.hikage.test.framework

import com.highcapable.hikage.project.Coordinates
import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.psi.KtFile

/**
 * Provides the shared light code-insight fixture used by Hikage regression tests.
 */
abstract class HikageCodeInsightTestCase : BasePlatformTestCase() {

    private val addedMavenLibraryNames = mutableListOf<String>()

    final override fun getTestDataPath() = "src/test/testData"

    /** Configures an in-memory Kotlin fixture for focused regression coverage. */
    protected fun configureKotlinByText(fileName: String, source: String) =
        myFixture.configureByText(fileName, source).asKtFile()

    /** Adds one source file to the light test project. */
    protected fun addProjectFile(path: String, source: String): PsiFile = myFixture.addFileToProject(path, source)

    /** Adds the Maven library identity used by [ProjectGate]. */
    protected fun enableHikageProject() = addMavenLibrary(Coordinates.CORE_MODULE)

    /** Adds the Maven library identity used by the runtime-attribute semantic gate. */
    protected fun enableHikageRuntimeAttribute() = addMavenLibrary(Coordinates.RUNTIME_ATTRIBUTE_MODULE)

    /** Adds minimal Android and Hikage source declarations used by focused code-insight tests. */
    protected fun installHikageTestApi() {
        addProjectFile(
            "android/content/Context.kt",
            """
            package android.content

            open class Context
            """.trimIndent()
        )
        addProjectFile(
            "android/util/AttributeSet.kt",
            """
            package android.util

            interface AttributeSet
            """.trimIndent()
        )
        addProjectFile(
            "android/view/View.kt",
            """
            package android.view

            import android.content.Context
            import android.util.AttributeSet

            open class View(
                val context: Context? = null,
                val attrs: AttributeSet? = null
            )
            """.trimIndent()
        )
        addProjectFile(
            "android/view/ViewGroup.kt",
            """
            package android.view

            import android.content.Context
            import android.util.AttributeSet

            open class ViewGroup(
                context: Context? = null,
                attrs: AttributeSet? = null
            ) : View(context, attrs) {
                open class LayoutParams
                open class MarginLayoutParams : LayoutParams()
            }
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
            "com/highcapable/hikage/annotation/HikageView.kt",
            """
            package com.highcapable.hikage.annotation

            import kotlin.reflect.KClass

            @Target(AnnotationTarget.CLASS)
            annotation class HikageView(
                val lparams: KClass<*> = Any::class,
                val alias: String = "",
                val attrs: Boolean = true,
                val init: Boolean = true,
                val performer: Boolean = true
            )
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/annotation/HikageViewDeclaration.kt",
            """
            package com.highcapable.hikage.annotation

            import kotlin.reflect.KClass

            @Target(AnnotationTarget.CLASS)
            annotation class HikageViewDeclaration(
                val view: KClass<*>,
                val lparams: KClass<*> = Any::class,
                val alias: String = "",
                val attrs: Boolean = true,
                val init: Boolean = true,
                val performer: Boolean = true
            )
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/Hikage.kt",
            """
            package com.highcapable.hikage.core

            import android.view.View
            import kotlin.jvm.JvmName

            class Hikage {
                class Performer
                class Delegate

                companion object {
                    fun create(performer: Performer.() -> Unit): Hikage = Hikage()
                    fun build(performer: Performer.() -> Unit): Delegate = Delegate()
                }

                val root: View get() = error("Test stub")

                inline fun <reified T : View> root(): T = error("Test stub")
                operator fun get(id: String): View = error("Test stub")
                fun getOrNull(id: String): View? = null

                @JvmName("getTyped")
                inline fun <reified T : View> get(id: String): T = error("Test stub")

                @JvmName("getOrNullTyped")
                inline fun <reified T : View> getOrNull(id: String): T? = null
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/base/HikagableUtils.kt",
            """
            package com.highcapable.hikage.core.base

            import android.view.ViewGroup
            import com.highcapable.hikage.core.Hikage
            import kotlin.jvm.JvmName

            fun Hikagable(performer: Hikage.Performer.() -> Unit): Hikage = Hikage.create(performer)

            @JvmName("HikagableTyped")
            inline fun <reified LP : ViewGroup.LayoutParams> Hikagable(
                noinline performer: Hikage.Performer.() -> Unit
            ): Hikage = Hikage.create(performer)
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/builder/HikageBuilder.kt",
            """
            package com.highcapable.hikage.core.builder

            import com.highcapable.hikage.core.Hikage

            interface HikageBuilder {
                fun build(): Hikage.Delegate
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/layout/LayoutParams.kt",
            """
            package com.highcapable.hikage.core.layout

            class LayoutParams private constructor() {
                companion object {
                    fun create() = LayoutParams()
                }
            }

            fun LayoutParams(
                matchParent: Boolean = false,
                widthMatchParent: Boolean = false,
                heightMatchParent: Boolean = false
            ) = LayoutParams.create()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/layout/Performer.kt",
            """
            package com.highcapable.hikage.core.layout

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.builder.HikageBuilder

            operator fun Hikage.Delegate.invoke(): Hikage = Hikage()
            operator fun HikageBuilder.invoke(): Hikage = Hikage()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/attribute/HikageAttributeUtils.kt",
            """
            package com.highcapable.hikage.core.attribute

            class AttributeScope {
                fun set(name: String, value: String = "") = Unit
            }

            fun HikageAttribute(block: AttributeScope.() -> Unit) = AttributeScope().block()
            fun set(name: String, value: String = "") = Unit
            fun namespace(name: String, block: AttributeScope.() -> Unit) = AttributeScope().block()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/attribute/HikageAttributeNamespaceUtils.kt",
            """
            package com.highcapable.hikage.core.attribute

            val android = "android"
            val app = "app"
            """.trimIndent()
        )
    }

    /** Adds concrete Android widget types used by layout reconstruction and lookup tests. */
    protected fun installAndroidWidgetTestApi() {
        addProjectFile(
            "android/widget/LinearLayout.kt",
            """
            package android.widget

            import android.view.ViewGroup

            open class LinearLayout : ViewGroup() {
                class LayoutParams : ViewGroup.MarginLayoutParams()
            }
            """.trimIndent()
        )
        addProjectFile(
            "android/widget/TextView.kt",
            """
            package android.widget

            import android.view.View

            open class TextView : View() {
                var text: CharSequence? = null
                var contentDescription: CharSequence? = null
            }
            """.trimIndent()
        )
    }

    /** Runs Analysis API-dependent assertions from a background read action. */
    protected fun <T> computeInBackgroundReadAction(action: () -> T): T =
        AppExecutorUtil.getAppExecutorService().submit<T> {
            ReadAction.computeBlocking<T, RuntimeException>(action)
        }.get()

    /** Selects [item] from the active completion lookup. */
    protected fun selectLookupElement(item: LookupElement, completionChar: Char = '\u0000') {
        val lookup = LookupManager.getInstance(project).activeLookup as? LookupImpl
        assertNotNull("Expected an active completion lookup.", lookup)
        lookup ?: return
        lookup.currentItem = item
        if (completionChar == '\u0000') myFixture.finishLookup(completionChar)
        else myFixture.type(completionChar)
    }

    /** Fails with the complete [source] when [expected] is absent. */
    protected fun assertContains(source: String, expected: String) = assertTrue(
        "Expected source to contain '$expected':\n$source",
        source.contains(expected)
    )

    /** Fails when [file] contains a parser error. */
    protected fun assertNoPsiErrors(file: PsiFile) {
        val errors = mutableListOf<PsiErrorElement>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiErrorElement) errors += element
                super.visitElement(element)
            }
        })
        assertTrue(
            errors.joinToString(prefix = "Kotlin PSI contains parser errors:\n") { error ->
                "${error.errorDescription} at ${error.textRange}"
            },
            errors.isEmpty()
        )
    }

    private fun PsiFile.asKtFile(): KtFile {
        assertTrue("Expected a Kotlin PSI file but got $fileType.", this is KtFile)
        return this as KtFile
    }

    /** Adds a coordinate-only module library whose name follows Gradle's imported-library shape. */
    protected fun addMavenLibrary(moduleCoordinate: String) {
        val libraryName = "Gradle: $moduleCoordinate:1.0.0"
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            libraryName,
            emptyList(),
            emptyList()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        addedMavenLibraryNames += libraryName
    }

    override fun tearDown() {
        try {
            if (addedMavenLibraryNames.isNotEmpty()) ModuleRootModificationUtil.updateModel(module) { model ->
                val libraryTable = model.moduleLibraryTable
                addedMavenLibraryNames.mapNotNull(libraryTable::getLibraryByName)
                    .forEach(libraryTable::removeLibrary)
            }
        } finally {
            addedMavenLibraryNames.clear()
            super.tearDown()
        }
    }
}