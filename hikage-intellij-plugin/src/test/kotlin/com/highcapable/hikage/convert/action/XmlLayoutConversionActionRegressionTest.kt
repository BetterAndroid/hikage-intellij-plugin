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
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.facet.FacetManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.android.facet.AndroidFacet

/**
 * Verifies the complete-selection presentation gate shared by XML layout conversion actions.
 */
class XmlLayoutConversionActionRegressionTest : HikageCodeInsightTestCase() {

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

    private companion object {
        val LAYOUT_XML = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"/>
        """.trimIndent()
    }
}