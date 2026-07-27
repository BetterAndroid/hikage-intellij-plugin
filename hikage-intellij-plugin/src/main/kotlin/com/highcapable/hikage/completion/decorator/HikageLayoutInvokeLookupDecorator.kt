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
 * This file is created by fankes on 2026/7/27.
 */
package com.highcapable.hikage.completion.decorator

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.addImport
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Adds the context-aware Hikage layout `invoke` import for delegate and builder values.
 */
object HikageLayoutInvokeLookupDecorator {

    /** Decorates [lookupElement] when it represents an invokable Hikage layout value. */
    fun decorateIfNeeded(lookupElement: LookupElement): LookupElement {
        val declaration = lookupElement.psiElement ?: return lookupElement
        if (!DeclarationMatcher.isHikageLayoutValue(declaration)) return lookupElement

        return HikageLayoutInvokeLookupElement(lookupElement)
    }

    private class HikageLayoutInvokeLookupElement(delegate: LookupElement) : LookupElementDecorator<LookupElement>(delegate) {

        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)

            val file = context.file as? KtFile ?: return
            context.commitDocument()
            file.addImport(KtPsiFactory.contextual(file), HikageSymbols.HIKAGE_LAYOUT_INVOKE_FUNCTION)
            context.commitDocument()
        }
    }
}