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
 * This file is created by fankes on 2026/7/21.
 */
package com.highcapable.hikage.intellij.completion.decorator

import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Adds an empty attribute-name string to completed Hikage attribute setter calls.
 */
internal object HikageAttributeSetLookupDecorator {

    fun decorateIfNeeded(lookupElement: LookupElement): LookupElement {
        if (lookupElement is HikageAttributeSetLookupElement || !lookupElement.isHikageAttributeSet()) return lookupElement
        return HikageAttributeSetLookupElement(lookupElement)
    }

    private fun LookupElement.isHikageAttributeSet() = sequenceOf(psiElement, psiElement?.navigationElement)
        .filterNotNull()
        .distinct()
        .any { element ->
            when (element) {
                is KtCallableDeclaration -> DeclarationMatcher.isHikageAttributeSetFunction(element)
                is PsiMethod -> DeclarationMatcher.isHikageAttributeSetFunction(element)
                else -> false
            }
        }

    private class HikageAttributeSetLookupElement(delegate: LookupElement) : LookupElementDecorator<LookupElement>(delegate) {

        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            context.insertAttributeNameArgument()
        }

        private fun InsertionContext.insertAttributeNameArgument() {
            val file = file as? KtFile ?: return
            commitDocument()
            val call = findInsertedCall(file) ?: return
            val argumentList = call.valueArgumentList ?: return
            if (argumentList.arguments.isNotEmpty() || argumentList.rightParenthesis == null) return

            val argument = argumentList.addArgument(KtPsiFactory.contextual(call).createArgument("\"\""))
            commitDocument()
            val literal = argument.getArgumentExpression() as? KtStringTemplateExpression ?: return
            val caretOffset = literal.textRange.startOffset + 1
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(caretOffset)
            tailOffset = caretOffset
            setAddCompletionChar(false)
        }

        private fun InsertionContext.findInsertedCall(file: KtFile): KtCallExpression? {
            val element = file.findElementAt(startOffset)
                ?: file.findElementAt((startOffset - 1).coerceAtLeast(0))
                ?: return null
            val call = PsiTreeUtil.getParentOfType(element, classOf<KtCallExpression>(), false) ?: return null
            return call.takeIf { expression -> expression.calleeExpression?.textMatches(lookupString) == true }
        }
    }
}