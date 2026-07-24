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
package com.highcapable.hikage.utils.android

import com.highcapable.hikage.utils.extension.failOpen
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Returns whether Android Studio's native resource icon cache can preview this drawable without layoutlib.
 */
object AndroidResource {

    /**
     * Returns whether Android Studio's native resource icon cache can preview this drawable without layoutlib.
     * Bitmap files and vector XML are supported; other drawable XML would install the deprecated render security manager.
     * @param resourceFile the [VirtualFile] to check.
     * @return [Boolean]
     */
    fun canUseNativeResourcePreview(resourceFile: VirtualFile) = !resourceFile.extension.equals("xml", true) || 
        failOpen(IOException::class) {
            val text = FileDocumentManager.getInstance().getCachedDocument(resourceFile)?.text
                ?: resourceFile.inputStream.bufferedReader().use { reader -> reader.readText() }
            text.contains("<vector")
        } == true
}