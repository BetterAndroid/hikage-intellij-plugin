/*
 * Hikage - A real-time AndroID View runtime powered by Kotlin DSL.
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
package com.highcapable.hikage.model

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Fully qualified Kotlin and JVM system symbols used by the IDE plugin.
 */
object SystemSymbols {

    /** The Kotlin root object type. */
    const val KOTLIN_ANY = "kotlin.Any"

    /** The Kotlin single-argument function type. */
    const val KOTLIN_FUNCTION1 = "kotlin.jvm.functions.Function1"

    /** The Kotlin `Unit` type. */
    const val KOTLIN_UNIT = "kotlin.Unit"

    /** The JVM representation of Kotlin's root object type. */
    const val JAVA_LANG_OBJECT = "java.lang.Object"

    /** The fully qualified name of the Hikage core library. */
    val KOTLIN_ANY_CLASS_ID = ClassId.topLevel(FqName(KOTLIN_ANY))
}