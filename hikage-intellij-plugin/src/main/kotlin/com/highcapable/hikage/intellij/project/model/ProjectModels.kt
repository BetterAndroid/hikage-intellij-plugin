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
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.intellij.project.model

import com.highcapable.hikage.intellij.project.model.entity.AndroidGradleModel
import com.highcapable.hikage.intellij.project.model.provider.ProjectModel
import com.highcapable.hikage.intellij.project.model.provider.ProjectModelProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Resolves the normalized project model that owns a project file.
 */
object ProjectModels {

    private val providers = listOf<ProjectModelProvider>(AndroidGradleModel)

    /**
     * Returns the synchronized project model that owns [file].
     */
    fun find(project: Project, file: VirtualFile): ProjectModel? {
        ProjectFileIndex.getInstance(project).getModuleForFile(file)
            ?.let(::create)
            ?.let { return it }

        // Generated declaration files live below Gradle's build directory and are often not a
        // source root. Fall back to the synchronized model directories to retain module ownership.
        val filePath = VfsUtilCore.virtualToIoFile(file).toPath().normalize()
        return ModuleManager.getInstance(project).modules.asSequence()
            .mapNotNull(::create)
            .map { model -> model to model.ownershipDepth(filePath) }
            .filter { (_, depth) -> depth >= 0 }
            .maxByOrNull { (_, depth) -> depth }
            ?.first
    }

    private fun create(module: Module) = providers.firstNotNullOfOrNull { provider -> provider.create(module) }

    private fun ProjectModel.ownershipDepth(filePath: Path) =
        (sequenceOf(buildDirectory) + sourceDirectories.asSequence())
            .map { directory -> directory.toPath().normalize() }
            .filter { directory -> filePath.startsWith(directory) }
            .maxOfOrNull(Path::getNameCount)
            ?: -1
}