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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.settings

import com.highcapable.hikage.convert.model.LayoutParamsConversionOption
import com.highcapable.hikage.convert.model.ViewConversionOption
import com.highcapable.hikage.project.Coordinates
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.settings.bundle.SettingsBundle
import com.highcapable.hikage.settings.service.SettingsService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Provides the Hikage project settings page.
 */
class RootConfigurable(private val project: Project) : SearchableConfigurable {

    private companion object {
        const val SETTINGS_ID = "${Coordinates.GROUP}.settings"
        const val XML_LAYOUT_CONVERSION_OPTION_WIDTH_GROUP = "xmlLayoutConversionOptions"
    }

    private val settings = SettingsService.getInstance(project)

    private var settingsPanel: DialogPanel? = null
    private var viewOptionComboBox: JComboBox<ViewConversionOption>? = null
    private var layoutParamsOptionComboBox: JComboBox<LayoutParamsConversionOption>? = null

    private var runtimeAttributeDependencyLink: ActionLink? = null
    private var isRuntimeAttributeDependencyPending = false

    override fun getId() = SETTINGS_ID
    override fun getDisplayName() = SettingsBundle.message("settings.page.name")

    override fun isModified() = settingsPanel?.isModified() == true

    override fun createComponent(): JComponent {
        val isRuntimeAttributeEnabled = HikageRuntimeAttributeGate.isEnabled(project)
        val panel = panel {
            row {
                label(SettingsBundle.message("settings.page.description"))
            }
            group(SettingsBundle.message("settings.group.hikage-dsl")) {
                row {
                    checkBox(SettingsBundle.message("settings.group.hikage-dsl.autofill-default-layout-params"))
                        .bindSelected(settings::isDefaultLayoutParamsAutoCompletionEnabled)
                        .contextHelp(SettingsBundle.message("settings.group.hikage-dsl.autofill-default-layout-params.help"))
                }
                row {
                    checkBox(SettingsBundle.message("settings.group.hikage-dsl.layout-lookup-preview-enabled"))
                        .bindSelected(settings::isLayoutLookupPreviewEnabled)
                        .contextHelp(SettingsBundle.message("settings.group.hikage-dsl.layout-lookup-preview-enabled.help"))
                }
            }
            group(SettingsBundle.message("settings.group.hikage-attribute")) {
                row {
                    checkBox(SettingsBundle.message("settings.group.hikage-attribute.resource-reference-preview-enabled"))
                        .bindSelected(settings::isAttributeResourceReferencePreviewEnabled)
                        .contextHelp(SettingsBundle.message("settings.group.hikage-attribute.resource-reference-preview-enabled.help"))
                }
            }
            group(SettingsBundle.message("settings.group.xml-layout-conversion")) {
                row(SettingsBundle.message("settings.group.xml-layout-conversion.view-option")) {
                    viewOptionComboBox = comboBox(
                        ViewConversionOption.entries,
                        SimpleListCellRenderer.create("", ::viewConversionOptionName)
                    ).widthGroup(XML_LAYOUT_CONVERSION_OPTION_WIDTH_GROUP)
                        .bindItem(
                            { settings.viewConversionOption },
                            { option -> settings.viewConversionOption = requireNotNull(option) }
                        )
                        .contextHelp(SettingsBundle.message("settings.group.xml-layout-conversion.view-option.help"))
                        .enabled(isRuntimeAttributeEnabled)
                        .component
                }
                row(SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option")) {
                    layoutParamsOptionComboBox = comboBox(
                        LayoutParamsConversionOption.entries,
                        SimpleListCellRenderer.create("", ::layoutParamsConversionOptionName)
                    ).widthGroup(XML_LAYOUT_CONVERSION_OPTION_WIDTH_GROUP)
                        .bindItem(
                            { settings.layoutParamsConversionOption },
                            { option -> settings.layoutParamsConversionOption = requireNotNull(option) }
                        )
                        .contextHelp(SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option.help"))
                        .enabled(isRuntimeAttributeEnabled)
                        .component
                }
                row {
                    runtimeAttributeDependencyLink = link(
                        SettingsBundle.message("settings.group.xml-layout-conversion.add-runtime-attribute-dependency")
                    ) { addRuntimeAttributeDependency() }
                        .visible(!isRuntimeAttributeEnabled)
                        .component
                }
            }
            group(SettingsBundle.message("settings.group.android-lint")) {
                row {
                    checkBox(SettingsBundle.message("settings.group.android-lint.mirror-enabled"))
                        .bindSelected(settings::isAndroidLintMirrorEnabled)
                        .contextHelp(SettingsBundle.message("settings.group.android-lint.mirror-enabled.help"))
                }
            }
        }
        settingsPanel = panel
        return panel
    }

    override fun apply() {
        val wasLayoutLookupPreviewEnabled = settings.isLayoutLookupPreviewEnabled
        val wasAttributeResourceReferencePreviewEnabled = settings.isAttributeResourceReferencePreviewEnabled
        val wasAndroidLintMirrorEnabled = settings.isAndroidLintMirrorEnabled

        settingsPanel?.apply()

        if (wasLayoutLookupPreviewEnabled != settings.isLayoutLookupPreviewEnabled ||
            wasAttributeResourceReferencePreviewEnabled != settings.isAttributeResourceReferencePreviewEnabled
        ) refreshFolding()
        if (wasAndroidLintMirrorEnabled != settings.isAndroidLintMirrorEnabled)
            DaemonCodeAnalyzer.getInstance(project).settingsChanged()
    }

    override fun reset() {
        settingsPanel?.reset()
        updateRuntimeAttributeControls()
    }

    override fun disposeUIResources() {
        settingsPanel = null
        viewOptionComboBox = null
        layoutParamsOptionComboBox = null
        runtimeAttributeDependencyLink = null
        isRuntimeAttributeDependencyPending = false
    }

    private fun addRuntimeAttributeDependency() {
        val module = HikageRuntimeAttributeGate.findDependencyTarget(project) ?: return
        if (!HikageRuntimeAttributeGate.addRuntimeAttributeDependency(module)) return

        isRuntimeAttributeDependencyPending = true
        updateRuntimeAttributeControls()
    }

    private fun updateRuntimeAttributeControls() {
        val isEnabled = isRuntimeAttributeDependencyPending || HikageRuntimeAttributeGate.isEnabled(project)
        viewOptionComboBox?.isEnabled = isEnabled
        layoutParamsOptionComboBox?.isEnabled = isEnabled
        runtimeAttributeDependencyLink?.isVisible = !isEnabled
    }

    private fun refreshFolding() {
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

    private fun viewConversionOptionName(option: ViewConversionOption) = when (option) {
        ViewConversionOption.FULLY_ATTRIBUTES -> SettingsBundle.message("settings.group.xml-layout-conversion.view-option.fully-attributes")
        ViewConversionOption.COMPATIBLE_MODE -> SettingsBundle.message("settings.group.xml-layout-conversion.view-option.compatible-mode")
        ViewConversionOption.GENERATE_CONSTRUCTOR_ONLY -> SettingsBundle.message("settings.group.xml-layout-conversion.view-option.generate-constructor-only")
    }

    private fun layoutParamsConversionOptionName(option: LayoutParamsConversionOption) = when (option) {
        LayoutParamsConversionOption.FULLY_ATTRIBUTES -> SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option.fully-attributes")
        LayoutParamsConversionOption.COMPATIBLE_MODE -> SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option.compatible-mode")
        LayoutParamsConversionOption.LAYOUT_PARAMS_ONLY -> SettingsBundle.message("settings.group.xml-layout-conversion.layout-params-option.layout-params-only")
    }

    // EditorFactory also exposes non-file UI editors, while folding requires a valid VirtualFile-backed document.
    private fun Editor.isAvailableForFoldingRefresh() = !isDisposed && FileDocumentManager.getInstance().getFile(document)?.isValid == true
}