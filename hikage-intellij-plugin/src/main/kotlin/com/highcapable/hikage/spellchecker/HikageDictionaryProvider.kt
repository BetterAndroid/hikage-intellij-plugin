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
 * This file is created by fankes on 2026/7/17.
 */
package com.highcapable.hikage.spellchecker

import com.highcapable.hikage.generated.PluginProperties
import com.intellij.spellchecker.BundledDictionaryProvider

/**
 * Provides Hikage terminology to the IDE's bundled spellchecker dictionary.
 */
class HikageDictionaryProvider : BundledDictionaryProvider {

    override fun getBundledDictionaries() = arrayOf(PluginProperties.PROJECT_BUNDLED_DICTIONARY_NAME)
}