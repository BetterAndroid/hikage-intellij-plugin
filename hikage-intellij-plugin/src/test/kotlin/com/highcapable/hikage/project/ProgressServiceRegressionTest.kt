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

import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the reusable project-scoped background progress lifecycle.
 */
class ProgressServiceRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies an indeterminate operation remains active until completion and returns its result on EDT. */
    fun testIndeterminateOperationCompletesOnEdt() {
        val service = ProgressService.getInstance(project)
        val started = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        val result = AtomicReference<String>()
        val callbackOnEdt = AtomicBoolean()

        val operation = service.runIndeterminate(
            title = "Progress fixture",
            onSuccess = { value ->
                result.set(value)
                callbackOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
            }
        ) {
            started.set(true)
            release.await()
            "completed"
        }

        waitUntil("The background operation did not start.") { started.get() }
        assertTrue(operation.isActive)
        assertNull(result.get())

        release.complete(Unit)
        waitUntil("The background operation did not finish on EDT.") {
            operation.isCompleted && result.get() != null
        }

        assertEquals("completed", result.get())
        assertTrue(callbackOnEdt.get())
    }

    /** Verifies a repeated operation key cancels the earlier task without publishing its result. */
    fun testRepeatedOperationKeyCancelsEarlierTask() {
        val service = ProgressService.getInstance(project)
        val firstStarted = AtomicBoolean()
        val firstPublished = AtomicBoolean()
        val secondResult = AtomicReference<String>()
        val operationKey = "progress-fixture"
        val firstOperation = service.runIndeterminate(
            title = "First progress fixture",
            operationKey = operationKey,
            onSuccess = { firstPublished.set(true) }
        ) {
            firstStarted.set(true)
            awaitCancellation()
        }

        waitUntil("The first keyed operation did not start.") { firstStarted.get() }
        val secondOperation = service.runIndeterminate(
            title = "Second progress fixture",
            operationKey = operationKey,
            onSuccess = secondResult::set
        ) { "second" }

        waitUntil("The replacement operation did not complete.") {
            firstOperation.isCancelled && secondOperation.isCompleted && secondResult.get() != null
        }

        assertFalse(firstPublished.get())
        assertEquals("second", secondResult.get())
    }

    /** Verifies cancellation thrown by an operation remains cancellation instead of becoming normal completion. */
    fun testOperationCancellationIsRethrown() {
        val failurePublished = AtomicBoolean()
        val operation = ProgressService.getInstance(project).runIndeterminate<Unit>(
            title = "Cancelled progress fixture",
            onFailure = { failurePublished.set(true) }
        ) {
            throw CancellationException("Progress fixture cancelled")
        }

        waitUntil("The cancelled operation did not finish.") { operation.isCompleted }

        assertTrue(operation.isCancelled)
        assertFalse(failurePublished.get())
    }

    private fun waitUntil(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
}