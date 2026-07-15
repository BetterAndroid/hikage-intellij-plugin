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
 * This file is created by fankes on 2026/7/15.
 */
package com.highcapable.hikage.intellij.dsl.extension

import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import org.jetbrains.kotlin.psi.KtAnnotationEntry

/**
 * Returns whether the annotation entry is a Hikage annotation with the given fully qualified name.
 * @param annotationFqName the fully qualified name of the annotation to check.
 * @return [Boolean]
 */
internal fun KtAnnotationEntry.isHikageAnnotation(annotationFqName: String): Boolean {
    val referenceText = typeReference?.text ?: return false
    return referenceText == annotationFqName || containingKtFile.resolveClassName(referenceText) == annotationFqName
}