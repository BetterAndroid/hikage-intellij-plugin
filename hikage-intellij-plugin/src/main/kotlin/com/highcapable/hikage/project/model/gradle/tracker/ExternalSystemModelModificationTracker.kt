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
package com.highcapable.hikage.project.model.gradle.tracker

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent

/**
 * Tracks completed external-system imports that can change generated Hikage declarations.
 */
@OptIn(KaPlatformInterface::class)
@Service(Service.Level.PROJECT)
class ExternalSystemModelModificationTracker(project: Project) : SimpleModificationTracker(), Disposable {

    companion object {

        /** Returns the external-system model tracker for [project]. */
        fun getInstance(project: Project) = project.service<ExternalSystemModelModificationTracker>()
    }

    init {
        project.messageBus.connect(this).subscribe(ProjectDataImportListener.TOPIC, object : ProjectDataImportListener {
            override fun onImportFinished(projectPath: String?) {
                incModificationCount()
                project.messageBus.syncPublisher(KotlinModificationEvent.TOPIC)
                    .onModification(KotlinGlobalSourceOutOfBlockModificationEvent)
            }
        })
    }

    /** Disposes the message-bus subscription with the project service. */
    override fun dispose() = Unit
}