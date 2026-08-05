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
 * This file is created by fankes on 2026/8/5.
 */
package com.highcapable.hikage.project

import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs reusable project operations with project-scoped background progress.
 */
@Service(Service.Level.PROJECT)
class ProgressService(private val project: Project, private val coroutineScope: CoroutineScope) {

    companion object {

        private val LOG = Logger.getInstance(classOf<ProgressService>())

        /**
         * Returns the progress service for [project].
         * @return [ProgressService]
         */
        fun getInstance(project: Project) = project.service<ProgressService>()
    }

    private val keyedOperations = ConcurrentHashMap<Any, Job>()

    /**
     * Runs [action] under a cancellable indeterminate progress item shown in the IDE status bar.
     * Reusing [operationKey] cancels the earlier operation, and completion callbacks run on EDT.
     * @return the launched project-scoped [Job].
     */
    fun <T> runIndeterminate(
        title: String,
        operationKey: Any? = null,
        onSuccess: (T) -> Unit = {},
        onFailure: (Throwable) -> Unit = LOG::error,
        action: suspend CoroutineScope.() -> T
    ): Job {
        lateinit var operation: Job

        operation = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withBackgroundProgress(project, title) {
                    reportProgressScope(size = 1) { reporter ->
                        reporter.indeterminateStep { action(this) }
                    }
                }
                if (!project.isDisposed) withContext(Dispatchers.EDT) { onSuccess(result) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!project.isDisposed) withContext(Dispatchers.EDT) { onFailure(error) }
            } finally {
                operationKey?.let { key -> keyedOperations.remove(key, operation) }
            }
        }

        operationKey?.let { key -> keyedOperations.put(key, operation)?.cancel() }
        operation.start()

        return operation
    }
}