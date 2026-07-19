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
 * This file is created by fankes on 2026/7/20.
 */
package com.highcapable.hikage.intellij.utils.extension

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CancellationException

/**
 * Returns whether Android Studio's native resource icon cache can preview this drawable without layoutlib.
 * Bitmap files and vector XML are supported; other drawable XML would install the deprecated render security manager.
 * @return [Boolean]
 */
fun VirtualFile.canUseNativeResourcePreview() = !extension.equals("xml", true) || runCatching {
    val text = FileDocumentManager.getInstance().getCachedDocument(this)?.text
        ?: inputStream.bufferedReader().use { reader -> reader.readText() }
    text.contains("<vector")
}.getOrElse { error ->
    if (error is ControlFlowException || error is CancellationException) throw error
    false
}