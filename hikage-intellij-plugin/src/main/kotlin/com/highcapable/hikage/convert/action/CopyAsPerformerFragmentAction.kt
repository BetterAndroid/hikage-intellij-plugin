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

import com.highcapable.hikage.convert.PerformerSnippetConverter
import com.highcapable.hikage.convert.action.resolver.XmlLayoutConversionTargetResolver
import com.highcapable.hikage.convert.bundle.ConversionBundle
import com.highcapable.hikage.convert.output.PerformerSnippetClipboardOutput
import com.highcapable.hikage.project.ProgressService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction

/**
 * Copies the selected Android XML layout as a Hikage Performer snippet.
 */
class CopyAsPerformerFragmentAction : XmlLayoutConversionAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val layout = XmlLayoutConversionTargetResolver.findSingleLayout(event) ?: return
        val project = layout.project
        val virtualFile = layout.virtualFile ?: return

        ProgressService.getInstance(project).runIndeterminate(
            title = ConversionBundle.message("conversion.progress.copyAsPerformerSnippet"),
            operationKey = listOf(javaClass, virtualFile),
            onSuccess = { outcome ->
                outcome?.let { result -> PerformerSnippetClipboardOutput.publish(project, result) }
            }
        ) {
            constrainedReadAction(
                ReadConstraint.withDocumentsCommitted(project),
                ReadConstraint.inSmartMode(project)
            ) {
                if (!virtualFile.isValid) return@constrainedReadAction null
                val currentLayout = XmlLayoutConversionTargetResolver.findSingleLayout(project, virtualFile)
                    ?: return@constrainedReadAction null
                PerformerSnippetConverter.convert(currentLayout)
            }
        }
    }
}