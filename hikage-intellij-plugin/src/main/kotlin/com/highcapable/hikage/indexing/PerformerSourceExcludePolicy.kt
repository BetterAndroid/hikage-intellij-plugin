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
package com.highcapable.hikage.indexing

import com.highcapable.hikage.project.model.gradle.GradleToolingModels
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Path

/**
 * Keeps synchronized Hikage KSP performer sources out of the IDE declaration scope.
 */
class PerformerSourceExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {

    companion object {

        private const val GENERATED_DIRECTORY_NAME = "generated"
        private const val KSP_DIRECTORY_NAME = "ksp"
        private const val KOTLIN_DIRECTORY_NAME = "kotlin"

        private val hikageWidgetPath = HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX.split(".").let { segments ->
            Path.of(segments.first(), *segments.drop(1).toTypedArray())
        }

        /** Returns the Hikage performer subtree for a generated KSP Kotlin source root. */
        fun excludedPath(sourcePath: String) = Path.of(sourcePath).normalize().let { path ->
            path.takeIf {
                it.fileName?.toString() == KOTLIN_DIRECTORY_NAME &&
                    it.parent?.parent?.fileName?.toString() == KSP_DIRECTORY_NAME &&
                    it.parent?.parent?.parent?.fileName?.toString() == GENERATED_DIRECTORY_NAME
            }?.resolve(hikageWidgetPath)?.toString()
        }
    }

    override fun getExcludeUrlsForProject() = ModuleManager.getInstance(project).modules
        .asSequence()
        .filter { module ->
            GradleToolingModels.find(module, HikageGradleToolingModel)?.isPluginApplied == true
        }
        .flatMap { module -> ModuleRootManager.getInstance(module).sourceRootUrls.asSequence() }
        .map(VfsUtilCore::urlToPath)
        .mapNotNull(::excludedPath)
        .map(VfsUtilCore::pathToUrl)
        .distinct()
        .toList()
        .toTypedArray()
}