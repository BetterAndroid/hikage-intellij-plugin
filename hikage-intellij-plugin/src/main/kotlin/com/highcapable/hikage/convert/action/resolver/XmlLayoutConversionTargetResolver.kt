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
package com.highcapable.hikage.convert.action.resolver

import com.android.resources.ResourceFolderType
import com.highcapable.hikage.project.ProjectGate
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import org.jetbrains.android.dom.AndroidResourceDomFileDescription
import org.jetbrains.android.facet.AndroidFacet

/**
 * Resolves complete action selections that are eligible Android XML layout resources.
 */
object XmlLayoutConversionTargetResolver {

    /**
     * Returns the only selected layout, or `null` when the complete context is not eligible.
     */
    fun findSingleLayout(event: AnActionEvent) = findSelectedLayouts(event)?.singleOrNull()

    /**
     * Re-resolves one [virtualFile] against the current [project] model before conversion execution.
     * @return the valid Android layout, or null when its current state is ineligible.
     */
    fun findSingleLayout(project: Project, virtualFile: VirtualFile) = findSelectedLayouts(project, listOf(virtualFile))?.singleOrNull()

    /**
     * Returns multiple selected layouts from one Android module, or `null` for a single or ineligible selection.
     */
    fun findMultipleLayouts(event: AnActionEvent) = findSelectedLayouts(event)?.takeIf { it.size > 1 }

    /**
     * Returns all selected layouts from one Android module, or `null` when any selected target is ineligible.
     */
    fun findSelectedLayouts(event: AnActionEvent): List<XmlFile>? {
        val project = event.project?.takeUnless { it.isDisposed } ?: return null
        val virtualFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.takeIf(Array<VirtualFile>::isNotEmpty)
            ?.toList()
            ?: listOfNotNull(event.getData(CommonDataKeys.PSI_FILE)?.virtualFile)

        return findSelectedLayouts(project, virtualFiles)
    }

    private fun findSelectedLayouts(project: Project, virtualFiles: List<VirtualFile>): List<XmlFile>? {
        if (project.isDisposed || !ProjectGate.from(project).isEnabled() || virtualFiles.isEmpty()) return null

        val psiManager = PsiManager.getInstance(project)
        val layouts = virtualFiles.map { virtualFile ->
            if (!virtualFile.isValid || virtualFile.isDirectory) return null
            val layout = psiManager.findFile(virtualFile) as? XmlFile ?: return null
            if (!layout.isValid || layout.rootTag == null) return null
            val module = ModuleUtilCore.findModuleForPsiElement(layout) ?: return null
            if (AndroidFacet.getInstance(module) == null ||
                !AndroidResourceDomFileDescription.isFileInResourceFolderType(layout, ResourceFolderType.LAYOUT)
            ) return null
            layout to module
        }

        if (layouts.map { (_, module) -> module }.distinct().size != 1) return null
        return layouts.map { (layout) -> layout }
    }
}