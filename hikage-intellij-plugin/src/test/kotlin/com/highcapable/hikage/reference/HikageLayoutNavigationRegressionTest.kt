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
 * This file is created by fankes on 2026/8/31.
 */
package com.highcapable.hikage.reference

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Verifies Layout ID and root navigation through real in-memory performer declarations.
 */
class HikageLayoutNavigationRegressionTest : HikageCodeInsightTestCase() {

    private companion object {
        val EXPECTED_IDS = setOf("primary_button", "secondary_button")
        val EXPECTED_BUILDER_IDS = setOf("item_preview", "item_icon")
    }

    /** Verifies a local View factory preserves the user performer as both navigation and reverse-search target. */
    fun testLocalViewFactoryPreservesPerformerNavigationTargets() {
        installNavigationTestApi()
        enableHikageProject()
        storeGradleModel()
        addProjectFile(
            "com/highcapable/hikage/fixture/view/FixtureImageButton.kt",
            """
            package com.highcapable.hikage.fixture.view

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView(alias = "FixtureButton", attrs = false, performer = false)
            class FixtureImageButton(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        assertEquals(listOf("FixtureButton"), PerformerDeclarations.resolve(project).map { declaration -> declaration.functionName })
        val file = configureKotlinByText(
            "LocalViewFactoryNavigation.kt",
            """
            package com.highcapable.hikage.fixture.layout

            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.fixture.view.FixtureImageButton
            import com.highcapable.hikage.widget.com.highcapable.hikage.fixture.view.FixtureButton

            val layout = Hikagable {
                fun createButton(
                    id: String,
                    icon: Int,
                    description: Int,
                    marginStart: Int = 0
                ) = FixtureButton(id = id) {}

                createButton(
                    id = "primary_button",
                    icon = 1,
                    description = 2
                )
                createButton(
                    id = "secondary_button",
                    icon = 3,
                    description = 4,
                    marginStart = 5
                )
            }

            val primary = layout.get<FixtureImageButton>("primary_button")
            val secondary = layout.get<FixtureImageButton>("secondary_button")
            """.trimIndent()
        )
        val performerCall = file.collectDescendantsOfType<KtCallExpression>()
            .first { call -> call.calleeExpression?.text == "FixtureButton" }
        val performer = requireNotNull(performerCall.calleeExpression)
        val resolver = HikageLayoutResolver.from(project)
        val lookups = file.collectDescendantsOfType<KtStringTemplateExpression>()
            .filter { expression -> expression.text.removeSurrounding("\"") in EXPECTED_IDS }
            .mapNotNull { expression -> resolver.resolveIdLookup(expression)?.let { lookup -> expression to lookup } }
        val manager = PsiManager.getInstance(project)

        assertEquals(EXPECTED_IDS.size, lookups.size)
        lookups.forEach { (expression, lookup) ->
            assertTrue(manager.areElementsEquivalent(performer, lookup.layoutId.performer))
            val reference = file.findReferenceAt(expression.textOffset + 1)
            assertNotNull("Expected a platform-selected reference for ${expression.text}.", reference)
            val target = reference?.resolve()
            assertNotNull("Expected ${expression.text} to resolve to its performer.", target)
            assertTrue(manager.areElementsEquivalent(performer, target))
        }

        val sourceElement = file.findElementAt(performer.textOffset)
        val targets = HikageLayoutLookupGotoDeclarationHandler()
            .getGotoDeclarationTargets(sourceElement, performer.textOffset, myFixture.editor)
        val usageTargets = targets.orEmpty().filterNot { target -> target is KtClass }
        val lookupExpressions = lookups.associate { (expression, lookup) -> lookup.layoutId.name to expression }
        val targetIds = usageTargets.mapNotNull { target ->
            val text = (target as? NavigationItem)?.presentation?.presentableText ?: return@mapNotNull null
            val id = text.removeSurrounding("\"")
            assertTrue(manager.areElementsEquivalent(lookupExpressions[id], target.navigationElement))
            id
        }.toSet()

        assertEquals(EXPECTED_IDS, targetIds)
        assertEquals(EXPECTED_IDS.size + 1, targets?.size)
        assertEquals("FixtureImageButton", (targets?.lastOrNull() as? KtClass)?.name)
    }

    /** Verifies shared and distinct constant ID sources navigate only to their actual lookup calls. */
    fun testBuilderDelegateCallbackPreservesLayoutIdNavigation() {
        installNavigationTestApi()
        enableHikageProject()
        storeGradleModel()
        addProjectFile(
            "com/highcapable/hikage/fixture/view/FixtureImageButton.kt",
            """
            package com.highcapable.hikage.fixture.view

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView(alias = "FixtureButton", attrs = false, performer = false)
            class FixtureImageButton(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        assertEquals(listOf("FixtureButton"), PerformerDeclarations.resolve(project).map { declaration -> declaration.functionName })
        val file = configureKotlinByText(
            "BuilderCallbackNavigation.kt",
            """
            package com.highcapable.hikage.fixture.layout

            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.core.builder.HikageBuilder
            import com.highcapable.hikage.fixture.view.FixtureImageButton
            import com.highcapable.hikage.widget.com.highcapable.hikage.fixture.view.FixtureButton

            object FixtureItemLayout : HikageBuilder {

                const val SHARED_PREVIEW_ID = "item_preview"
                const val DECLARATION_ICON_ID = "item_icon"

                override fun build() = Hikagable(Unit) {
                    FixtureButton(id = SHARED_PREVIEW_ID) {}
                    FixtureButton(id = DECLARATION_ICON_ID) {}
                }
            }

            fun bindItem(
                delegate: Hikage.Delegate<*>,
                viewHolder: (hikage: Hikage) -> Unit
            ) = Unit

            class FixtureBinder {

                private companion object {
                    const val LOOKUP_ICON_ID = "item_icon"
                }

                fun bind() {
                    bindItem(FixtureItemLayout.build()) { hikage ->
                        val preview = hikage.get<FixtureImageButton>(FixtureItemLayout.SHARED_PREVIEW_ID)
                        val icon = hikage.get<FixtureImageButton>(LOOKUP_ICON_ID)
                    }
                }
            }
            """.trimIndent()
        )
        val resolver = HikageLayoutResolver.from(project)
        val performers = file.collectDescendantsOfType<KtCallExpression>()
            .filter { call -> call.calleeExpression?.text == "FixtureButton" }
            .associateBy { call ->
                requireNotNull(resolver.resolveIdDeclaration(requireNotNull(call.calleeExpression))).name
            }
        val lookups = file.collectDescendantsOfType<KtCallExpression>()
            .mapNotNull { call ->
                resolver.resolveIdLookup(call)?.let { lookup -> lookup.layoutId.name to lookup }
            }
            .toMap()
        val manager = PsiManager.getInstance(project)

        assertEquals(EXPECTED_BUILDER_IDS, performers.keys)
        assertEquals(EXPECTED_BUILDER_IDS, lookups.keys)
        EXPECTED_BUILDER_IDS.forEach { id ->
            val performer = requireNotNull(performers[id]?.calleeExpression)
            val lookup = requireNotNull(lookups[id])

            val sourceElement = file.findElementAt(performer.textOffset)
            val targets = HikageLayoutLookupGotoDeclarationHandler()
                .getGotoDeclarationTargets(sourceElement, performer.textOffset, myFixture.editor)
            val usageTargets = targets.orEmpty().filterNot { target -> target is KtClass }
            val usageTarget = usageTargets.singleOrNull()

            assertEquals(1, usageTargets.size)
            assertEquals("\"$id\"", (usageTarget as? NavigationItem)?.presentation?.presentableText)
            assertTrue(manager.areElementsEquivalent(lookup.idExpression, usageTarget?.navigationElement))
            assertEquals(2, targets?.size)
            assertEquals("FixtureImageButton", (targets?.lastOrNull() as? KtClass)?.name)
        }
    }

    /** Verifies a directly supplied performer lambda remains the callback Hikage root source. */
    fun testDirectPerformerCallbackPreservesRootNavigation() {
        installNavigationTestApi()
        enableHikageProject()
        storeGradleModel()
        addProjectFile(
            "com/highcapable/hikage/fixture/view/FixtureImageButton.kt",
            """
            package com.highcapable.hikage.fixture.view

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView(alias = "FixtureButton", attrs = false, performer = false)
            class FixtureImageButton(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        assertEquals(listOf("FixtureButton"), PerformerDeclarations.resolve(project).map { declaration -> declaration.functionName })
        val file = configureKotlinByText(
            "PerformerCallbackNavigation.kt",
            """
            package com.highcapable.hikage.fixture.layout

            import android.view.ViewGroup
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.HikagePerformer
            import com.highcapable.hikage.fixture.view.FixtureImageButton
            import com.highcapable.hikage.widget.com.highcapable.hikage.fixture.view.FixtureButton

            fun bindItem(
                Hikagable: HikagePerformer<ViewGroup.LayoutParams>,
                viewHolder: (hikage: Hikage) -> Unit
            ) = Unit

            fun bind() {
                bindItem(
                    Hikagable = {
                        FixtureButton() {}
                    }
                ) { hikage ->
                    val root = hikage.root<FixtureImageButton>()
                }
            }
            """.trimIndent()
        )
        val performer = requireNotNull(file.collectDescendantsOfType<KtCallExpression>()
            .first { call -> call.calleeExpression?.text == "FixtureButton" }
            .calleeExpression)
        val rootCall = file.collectDescendantsOfType<KtCallExpression>()
            .first { call -> call.calleeExpression?.text == "root" }
        val rootCallee = requireNotNull(rootCall.calleeExpression)
        myFixture.editor.foldingModel.runBatchFoldingOperation {
            requireNotNull(myFixture.editor.foldingModel.addFoldRegion(
                rootCall.textRange.startOffset,
                rootCall.textRange.endOffset,
                "root"
            )).isExpanded = false
        }

        val sourceElement = file.findElementAt(rootCallee.textOffset)
        val targets = HikageLayoutLookupGotoDeclarationHandler()
            .getGotoDeclarationTargets(sourceElement, rootCallee.textOffset, myFixture.editor)

        assertEquals(1, targets?.size)
        assertTrue(PsiManager.getInstance(project).areElementsEquivalent(performer, targets?.singleOrNull()))
    }

    private fun installNavigationTestApi() {
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

            open class ViewGroup : View() {
                open class LayoutParams
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
            "com/highcapable/hikage/core/Hikage.kt",
            """
            package com.highcapable.hikage.core

            import android.view.View
            import android.view.ViewGroup

            class Hikage {
                class Performer<LP : ViewGroup.LayoutParams>
                class Delegate<LP : ViewGroup.LayoutParams>

                inline fun <reified V : View> get(id: String): V = error("Test stub")
                inline fun <reified V : View> root(): V = error("Test stub")
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/base/Hikagable.kt",
            """
            package com.highcapable.hikage.core.base

            import android.view.ViewGroup
            import com.highcapable.hikage.core.Hikage

            typealias HikagePerformer<LP> = Hikage.Performer<LP>.() -> Unit

            fun Hikagable(performer: HikagePerformer<ViewGroup.LayoutParams>) = Hikage()

            fun Hikagable(
                delegateFactory: Unit,
                performer: HikagePerformer<ViewGroup.LayoutParams>
            ) = Hikage.Delegate<ViewGroup.LayoutParams>()
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/builder/HikageBuilder.kt",
            """
            package com.highcapable.hikage.core.builder

            import com.highcapable.hikage.core.Hikage

            interface HikageBuilder {
                fun build(): Hikage.Delegate<*>
            }
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/base/HikageView.kt",
            """
            package com.highcapable.hikage.core.base

            typealias HikageView<V> = V.() -> Unit
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/layout/LayoutParams.kt",
            """
            package com.highcapable.hikage.core.layout

            class LayoutParams
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/layout/Performer.kt",
            """
            package com.highcapable.hikage.core.layout

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import android.view.ViewGroup
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import kotlin.reflect.KClass

            @Hikagable
            fun <V : View, LP : ViewGroup.LayoutParams> Hikage.Performer<LP>.View(
                viewClass: KClass<V>,
                factory: ((Context, AttributeSet?) -> V)? = null,
                lparams: LayoutParams? = null,
                id: String? = null
            ): V = error("Test stub")
            """.trimIndent()
        )
    }

    private fun storeGradleModel() {
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val moduleData = ModuleData(
            module.name,
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            module.name,
            rootPath,
            rootPath
        )
        projectNode.createChild(ProjectKeys.MODULE, moduleData).createChild(
            HikageGradleToolingModel.key,
            DefaultHikageGradleModel(
                isPluginApplied = true,
                isCompilerEnabled = true,
                viewDeclarationFiles = emptyList(),
                optionalViewDeclarationFiles = emptyList(),
                strictViewDeclarationInputFiles = emptyList(),
                optionalViewDeclarationInputArtifacts = emptyList()
            )
        )
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            moduleData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )
    }

    private class StoredExternalProjectInfo(
        private val systemId: ProjectSystemId,
        private val path: String,
        private val structure: DataNode<ProjectData>
    ) : ExternalProjectInfo {
        override fun getProjectSystemId() = systemId
        override fun getExternalProjectPath() = path
        override fun getExternalProjectStructure() = structure
        override fun getLastSuccessfulImportTimestamp() = 1L
        override fun getLastImportTimestamp() = 1L
        override fun getBuildNumber() = ""
        override fun copy() = StoredExternalProjectInfo(systemId, path, structure.graphCopy())
    }
}