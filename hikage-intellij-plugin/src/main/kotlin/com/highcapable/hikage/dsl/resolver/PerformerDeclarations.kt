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
package com.highcapable.hikage.dsl.resolver

import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.intellij.openapi.project.Project

/**
 * Provides project-level Hikage performer declarations.
 */
object PerformerDeclarations {

    /**
     * Returns the cached list of [PerformerDeclaration] for the given [project].
     * @param project the [Project] to resolve declarations for.
     * @return [List]<[PerformerDeclaration]>
     */
    fun resolve(project: Project) = PerformerDeclarationCache.getInstance(project).resolve()

    /**
     * Returns the View classes that have more than one active project declaration source.
     * @param project the [Project] to resolve duplicate view classes for.
     * @return [Set]<[String]>
     */
    fun duplicateViewClasses(project: Project) = PerformerDeclarationCache.getInstance(project).duplicateViewClasses()
}