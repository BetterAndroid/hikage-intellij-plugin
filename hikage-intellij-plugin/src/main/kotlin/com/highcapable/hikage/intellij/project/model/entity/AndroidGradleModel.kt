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
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.intellij.project.model.entity

import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.highcapable.hikage.intellij.project.model.provider.ProjectModel
import com.highcapable.hikage.intellij.project.model.provider.ProjectModelProvider
import com.intellij.openapi.module.Module

/**
 * Adapts Android Studio's synchronized Gradle model into [ProjectModel].
 */
object AndroidGradleModel : ProjectModelProvider {

    override fun create(module: Module) = GradleAndroidModel.get(module)?.let {
        ProjectModel(
            module = module,
            rootDirectory = it.rootDirPath,
            buildDirectory = it.androidProject.buildFolder,
            sourceDirectories = it.allSourceProviders
                .flatMap { sourceProvider -> sourceProvider.sourceDirectories() }
                .toSet()
        )
    }

    private fun IdeSourceProvider.sourceDirectories() = javaDirectories + kotlinDirectories + resourcesDirectories
}