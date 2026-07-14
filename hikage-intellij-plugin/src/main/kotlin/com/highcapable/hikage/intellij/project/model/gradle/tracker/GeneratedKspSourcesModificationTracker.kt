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
package com.highcapable.hikage.intellij.project.model.gradle.tracker

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent

/**
 * Tracks KSP generated-source changes and invalidates K2 resolve extensions that mirror them.
 */
@OptIn(KaPlatformInterface::class)
@Service(Service.Level.PROJECT)
class GeneratedKspSourcesModificationTracker(project: Project) : SimpleModificationTracker(), Disposable {

    companion object {

        private const val GENERATED_KSP_DIRECTORY_SUFFIX = "/generated/ksp"

        /** Returns the generated KSP source tracker for [project]. */
        fun getInstance(project: Project) = project.service<GeneratedKspSourcesModificationTracker>()
    }

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.none { event -> event.affectsGeneratedKspSources() }) return

                incModificationCount()
                project.messageBus.syncPublisher(KotlinModificationEvent.TOPIC)
                    .onModification(KotlinGlobalSourceOutOfBlockModificationEvent)
            }
        })
    }

    private fun VFileEvent.affectsGeneratedKspSources() = path.replace('\\', '/').let { eventPath ->
        eventPath.endsWith(GENERATED_KSP_DIRECTORY_SUFFIX) || eventPath.contains("$GENERATED_KSP_DIRECTORY_SUFFIX/")
    }

    /** Disposes the message-bus subscription with the project service. */
    override fun dispose() = Unit
}