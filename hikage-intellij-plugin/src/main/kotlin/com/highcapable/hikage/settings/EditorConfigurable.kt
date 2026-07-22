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
 * This file is created by fankes on 2026/7/23.
 */
package com.highcapable.hikage.settings

import com.highcapable.hikage.settings.bundle.SettingsBundle
import com.highcapable.hikage.settings.service.SettingsService
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.JComponent

/**
 * Provides Hikage editor settings.
 */
class EditorConfigurable(private val project: Project) : SearchableConfigurable {

    private companion object {
        const val SETTINGS_ID = "com.highcapable.hikage.settings.editor"
    }

    private val settings = SettingsService.getInstance(project)
    private var settingsPanel: DialogPanel? = null

    override fun getId() = SETTINGS_ID
    override fun getDisplayName() = SettingsBundle.message("settings.page.editor")

    override fun isModified() = settingsPanel?.isModified() == true

    override fun createComponent(): JComponent {
        val panel = panel {
            group(SettingsBundle.message("settings.page.editor.group.layout-lookup")) {
                row {
                    checkBox(SettingsBundle.message("settings.page.editor.group.layout-lookup.preview-enabled"))
                        .bindSelected(settings::isLayoutLookupPreviewEnabled)
                        .contextHelp(SettingsBundle.message("settings.page.editor.group.layout-lookup.preview-enabled.help"))
                }
            }
        }
        settingsPanel = panel
        return panel
    }

    override fun apply() {
        val wasEnabled = settings.isLayoutLookupPreviewEnabled
        settingsPanel?.apply()
        if (wasEnabled != settings.isLayoutLookupPreviewEnabled) refreshLayoutLookupFolding()
    }

    override fun reset() {
        settingsPanel?.reset()
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }

    private fun refreshLayoutLookupFolding() {
        val foldingManager = CodeFoldingManager.getInstance(project)
        val modalityState = ModalityState.current()

        EditorFactory.getInstance().allEditors
            .asSequence()
            .filter { editor -> editor.project === project && editor.isAvailableForFoldingRefresh() }
            .forEach { editor ->
                ReadAction.nonBlocking<Runnable?> {
                    if (project.isDisposed || !editor.isAvailableForFoldingRefresh()) null
                    else foldingManager.updateFoldRegionsAsync(editor, true)
                }.withDocumentsCommitted(project)
                    .expireWith(settings)
                    .finishOnUiThread(modalityState) { update ->
                        if (!project.isDisposed && editor.isAvailableForFoldingRefresh()) update?.run()
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            }
    }

    // EditorFactory also exposes non-file UI editors, while folding requires a valid VirtualFile-backed document.
    private fun Editor.isAvailableForFoldingRefresh() =
        !isDisposed && FileDocumentManager.getInstance().getFile(document)?.isValid == true
}