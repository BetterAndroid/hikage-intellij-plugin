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
 * This file is created by fankes on 2026/7/24.
 */
package com.highcapable.hikage.utils.extension

import com.intellij.openapi.diagnostic.ControlFlowException
import java.util.concurrent.CancellationException
import kotlin.reflect.KClass

/**
 * Executes [action] and returns null when an ordinary exception prevents an optional IDE result.
 * IntelliJ control-flow and cancellation exceptions are always rethrown.
 * @param action the optional IDE operation to execute.
 * @return [T] the action result or null.
 */
inline fun <T> failOpen(action: () -> T) = try {
    action()
} catch (error: Exception) {
    if (error is ControlFlowException || error is CancellationException) throw error
    null
}

/**
 * Executes [action] and returns null only for the declared recoverable [exceptionTypes].
 * IntelliJ control-flow, cancellation, and every undeclared exception are rethrown.
 * @param exceptionTypes the recoverable exception types.
 * @param action the optional IDE operation to execute.
 * @return [T] the action result or null.
 */
inline fun <T> failOpen(vararg exceptionTypes: KClass<out Exception>, action: () -> T) = try {
    action()
} catch (error: Exception) {
    if (error is ControlFlowException || error is CancellationException) throw error
    if (exceptionTypes.any { exceptionType -> exceptionType.isInstance(error) }) null else throw error
}