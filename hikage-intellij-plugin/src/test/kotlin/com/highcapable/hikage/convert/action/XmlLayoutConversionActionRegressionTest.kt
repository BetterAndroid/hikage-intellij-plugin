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
 * This file is created by fankes on 2026/7/26.
 */
package com.highcapable.hikage.convert.action

import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.ScopeType
import com.android.tools.idea.projectsystem.SourceProviders
import com.highcapable.hikage.convert.action.resolver.XmlLayoutConversionTargetResolver
import com.highcapable.hikage.convert.output.KotlinSnippetPasteProcessor
import com.highcapable.hikage.notification.bundle.NotificationBundle
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.facet.FacetManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.android.facet.AndroidFacet
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Verifies the complete-selection presentation gate shared by XML layout conversion actions.
 */
class XmlLayoutConversionActionRegressionTest : HikageCodeInsightTestCase() {

    private companion object {
        val LAYOUT_XML = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"/>
        """.trimIndent()
    }

    /** Verifies single-layout actions require both the Hikage project gate and an Android layout resource. */
    fun testSingleLayoutPresentationRequiresHikageAndroidLayout() {
        val action = CopyAsHikagablePropertyAction()
        val layout = addProjectFile("app/src/main/res/layout/activity_main.xml", LAYOUT_XML)

        assertUnavailable(action, layoutContext(layout))
        enableHikageProject()
        assertUnavailable(action, layoutContext(layout))

        installAndroidSourceProvider(layout)

        assertAvailable(action, layoutContext(layout))
        assertAvailable(CopyAsPerformerFragmentAction(), layoutContext(layout))
        assertAvailable(QuickXmlLayoutConversionAction(), layoutContext(layout))
        assertUnavailable(ConvertSelectedXmlLayoutsAction(), layoutContext(layout))
        assertAvailable(XmlLayoutConversionActionGroup(), layoutContext(layout, useVirtualFileSelection = false))
        assertSame(layout, XmlLayoutConversionTargetResolver.findSingleLayout(project, layout.virtualFile))
    }

    /** Verifies batch actions require multiple layouts and reject a mixed selection instead of filtering targets. */
    fun testBatchPresentationRequiresMultipleLayoutsAndRejectsTheCompleteMixedSelection() {
        enableHikageProject()
        val firstLayout = addProjectFile("app/src/main/res/layout/first.xml", LAYOUT_XML)
        val secondLayout = addProjectFile("app/src/main/res/layout-land/second.xml", LAYOUT_XML)
        val manifest = addProjectFile("app/src/main/AndroidManifest.xml", "<manifest/>")
        installAndroidSourceProvider(firstLayout, manifest)
        val action = ConvertSelectedXmlLayoutsAction()

        assertAvailable(action, layoutContext(firstLayout, secondLayout))
        assertAvailable(XmlLayoutConversionActionGroup(), layoutContext(firstLayout, secondLayout))
        assertUnavailable(QuickXmlLayoutConversionAction(), layoutContext(firstLayout, secondLayout))
        assertUnavailable(action, layoutContext(firstLayout, manifest))
    }

    /** Verifies Property action execution, progress delivery, and no-dialog generic fallback. */
    fun testPropertyActionCopiesGenericFallbackLayout() {
        installHikageTestApi()
        enableHikageProject()
        addProjectFile(
            "com/highcapable/hikage/property/fixture/FallbackView.kt",
            """
            package com.highcapable.hikage.property.fixture

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class FallbackView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val layout = addProjectFile(
            "app/src/main/res/layout/profile_card.xml",
            """
            <com.highcapable.hikage.property.fixture.FallbackView
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/profile"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"/>
            """.trimIndent()
        )
        installAndroidSourceProvider(layout)
        val clipboard = CopyPasteManager.getInstance()
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setContents(StringSelection("existing"))
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.title == NotificationBundle.message("notification.conversion.title"))
                    notifications += notification
            }
        })
        val action = CopyAsHikagablePropertyAction()

        action.actionPerformed(TestActionEvent.createTestEvent(action, layoutContext(layout)))
        PlatformTestUtil.waitWithEventsDispatching(
            "The Hikagable Property action did not publish its conversion result.",
            {
                clipboard.getContents<String>(DataFlavor.stringFlavor)
                    ?.let { source ->
                        source.startsWith("val ProfileCard = Hikagable<ViewGroup.MarginLayoutParams> {") &&
                            source.contains("View<FallbackView>(") &&
                            source.contains("updateMarginsRelative(start = 12.dp)") &&
                            !source.contains("attrs =")
                    } == true && notifications.isNotEmpty()
            },
            10
        )

        assertTrue(notifications.single().content.startsWith(
            "Hikagable property copied with 1 conversion reports."
        ))
        val importData = requireNotNull(clipboard.contents)
            .getTransferData(KotlinSnippetPasteProcessor.TransferableData.dataFlavor)
            as KotlinSnippetPasteProcessor.TransferableData
        assertTrue(importData.imports.containsAll(listOf(
            HikageSymbols.HIKAGABLE_FUNCTION,
            HikageSymbols.HIKAGE_LAYOUT_VIEW_FUNCTION,
            "android.view.ViewGroup",
            "androidx.core.view.updateMarginsRelative",
            "com.highcapable.hikage.property.fixture.FallbackView"
        )))
        assertTrue(importData.imports.none { importName -> importName.endsWith(".*") })
    }

    /** Verifies the default root contract keeps the concise untyped Hikagable form. */
    fun testPropertyActionKeepsDefaultRootContractImplicit() {
        installHikageTestApi()
        enableHikageProject()
        val layout = addProjectFile(
            "app/src/main/res/layout/simple_card.xml",
            """
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"/>
            """.trimIndent()
        )
        installAndroidSourceProvider(layout)
        val action = CopyAsHikagablePropertyAction()

        action.actionPerformed(TestActionEvent.createTestEvent(action, layoutContext(layout)))
        PlatformTestUtil.waitWithEventsDispatching(
            "The default Hikagable Property root was not copied.",
            {
                CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
                    ?.startsWith("val SimpleCard = Hikagable {") == true
            },
            10
        )

        val source = requireNotNull(
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        )
        assertFalse(source.contains("Hikagable<"))
        assertFalse(source.contains("attrs ="))
    }

    private fun installAndroidSourceProvider(layout: PsiFile, manifest: PsiFile? = null) {
        val resolvedManifest = manifest ?: addProjectFile("app/src/main/AndroidManifest.xml", "<manifest/>")
        val resourceDirectory = requireNotNull(layout.virtualFile.parent?.parent)
        val facet = AndroidFacet.getInstance(module) ?: WriteAction.compute<AndroidFacet, RuntimeException> {
            FacetManager.getInstance(module).addFacet(AndroidFacet.getFacetType(), AndroidFacet.NAME, null)
        }
        val sourceProvider = NamedIdeaSourceProviderBuilder.create("main", resolvedManifest.virtualFile.url)
            .withScopeType(ScopeType.MAIN)
            .withResDirectoryUrls(listOf(resourceDirectory.url))
            .build()
        SourceProviders.replaceForTest(facet, testRootDisposable, sourceProvider)
    }

    private fun layoutContext(vararg files: PsiFile, useVirtualFileSelection: Boolean = true): DataContext {
        val builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
        if (useVirtualFileSelection)
            builder.add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files.map(PsiFile::getVirtualFile).toTypedArray())
        else builder.add(CommonDataKeys.PSI_FILE, files.single())
        return builder.build()
    }

    private fun assertAvailable(action: AnAction, context: DataContext) {
        val event = TestActionEvent.createTestEvent(action, context)
        ActionUtil.updateAction(action, event)
        assertTrue(event.presentation.isEnabled)
        assertTrue(event.presentation.isVisible)
    }

    private fun assertUnavailable(action: AnAction, context: DataContext) {
        val event = TestActionEvent.createTestEvent(action, context)
        ActionUtil.updateAction(action, event)
        assertFalse(event.presentation.isEnabled)
        assertFalse(event.presentation.isVisible)
    }
}