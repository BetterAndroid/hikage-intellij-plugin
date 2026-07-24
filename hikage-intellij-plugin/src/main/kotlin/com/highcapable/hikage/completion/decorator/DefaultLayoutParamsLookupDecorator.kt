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
 * This file is created by fankes on 2026/7/13.
 */
package com.highcapable.hikage.completion.decorator

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Adds a default `lparams = LayoutParams()` block for `Hikagable` functions that expose one layout-params parameter.
 */
internal object DefaultLayoutParamsLookupDecorator {

    private const val DEFAULT_LAYOUT_PARAMS_ARGUMENT_PREFIX = "\n    "
    private const val DEFAULT_LAYOUT_PARAMS_ARGUMENT_SUFFIX = "\n"
    private const val OPEN_PAREN = '('
    private const val CLOSE_PAREN = ')'
    private const val LAYOUT_PARAMS_ARGUMENT_NAME = "lparams"

    fun decorateIfNeeded(lookupElement: LookupElement): LookupElement {
        val declaration = lookupElement.psiElement as? KtNamedFunction ?: return lookupElement
        if (!DeclarationMatcher.shouldCompleteDefaultLayoutParams(declaration)) return lookupElement
        val layoutParamsArgumentName = DeclarationMatcher.findDefaultLayoutParamsParameterName(declaration)
            ?: LAYOUT_PARAMS_ARGUMENT_NAME

        return DefaultLayoutParamsLookupElement(lookupElement, layoutParamsArgumentName)
    }

    private class DefaultLayoutParamsLookupElement(
        delegate: LookupElement,
        private val layoutParamsArgumentName: String
    ) : LookupElementDecorator<LookupElement>(delegate) {

        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            context.insertDefaultLayoutParamsArgument()
        }

        private fun InsertionContext.insertDefaultLayoutParamsArgument() {
            val file = file as? KtFile ?: return
            commitDocument()

            when (insertDefaultLayoutParamsArgument(file)) {
                InsertDefaultLayoutParamsResult.INSERTED,
                InsertDefaultLayoutParamsResult.SKIPPED -> return
                InsertDefaultLayoutParamsResult.CALL_NOT_FOUND -> insertDefaultLayoutParamsArgumentText()
            }
        }

        private fun InsertionContext.insertDefaultLayoutParamsArgumentText() {
            val document = document
            val editor = editor
            val caretOffset = editor.caretModel.offset
            val chars = document.charsSequence
            val insertionOffset = when {
                chars.getOrNull(caretOffset - 1) == OPEN_PAREN && chars.getOrNull(caretOffset) == CLOSE_PAREN -> caretOffset
                chars.getOrNull(caretOffset - 2) == OPEN_PAREN && chars.getOrNull(caretOffset - 1) == CLOSE_PAREN -> caretOffset - 1
                else -> null
            }
            val argumentText = "lparams = ${HikageSymbols.HIKAGE_LAYOUT_PARAMS_NAME}()"

            if (insertionOffset == null) return

            val insertedText = "$DEFAULT_LAYOUT_PARAMS_ARGUMENT_PREFIX$argumentText$DEFAULT_LAYOUT_PARAMS_ARGUMENT_SUFFIX"
            document.insertString(insertionOffset, insertedText)
            selectArgument(insertionOffset + DEFAULT_LAYOUT_PARAMS_ARGUMENT_PREFIX.length, argumentText.length)

            commitDocument()
        }

        private fun InsertionContext.insertDefaultLayoutParamsArgument(file: KtFile): InsertDefaultLayoutParamsResult {
            val callExpression = findInsertedCallExpression(file) ?: return InsertDefaultLayoutParamsResult.CALL_NOT_FOUND
            if (!callExpression.hasBlankArgumentList()) return InsertDefaultLayoutParamsResult.SKIPPED

            val psiFactory = KtPsiFactory.contextual(file)
            val addedArgument = callExpression.setDefaultLayoutParamsArguments(psiFactory)?.arguments?.singleOrNull()
                ?: return InsertDefaultLayoutParamsResult.SKIPPED

            file.ensureLayoutParamsImport(psiFactory)
            commitDocument()
            selectArgument(addedArgument.textRange.startOffset, addedArgument.textLength)
            return InsertDefaultLayoutParamsResult.INSERTED
        }

        private fun InsertionContext.findInsertedCallExpression(file: KtFile): KtCallExpression? {
            val element = file.findElementAt(startOffset) ?: file.findElementAt((startOffset - 1).coerceAtLeast(0)) ?: return null
            val callExpression = PsiTreeUtil.getParentOfType(element, classOf<KtCallExpression>(), false) ?: return null
            return callExpression.takeIf { expression -> expression.calleeExpression?.textMatches(lookupString) == true }
        }

        private fun KtCallExpression.setDefaultLayoutParamsArguments(psiFactory: KtPsiFactory): KtValueArgumentList? {
            val valueArgumentList = valueArgumentList
            val argumentList = psiFactory.createCallArguments(
                "($DEFAULT_LAYOUT_PARAMS_ARGUMENT_PREFIX$layoutParamsArgumentName = ${HikageSymbols.HIKAGE_LAYOUT_PARAMS_NAME}()" +
                    DEFAULT_LAYOUT_PARAMS_ARGUMENT_SUFFIX +
                    ")"
            )

            if (valueArgumentList != null) return valueArgumentList.replace(argumentList) as? KtValueArgumentList

            val calleeExpression = calleeExpression ?: return null
            return addAfter(argumentList, calleeExpression) as? KtValueArgumentList
        }

        private fun KtCallExpression.hasBlankArgumentList() = valueArgumentList
            ?.text
            ?.removeSurrounding(OPEN_PAREN.toString(), CLOSE_PAREN.toString())
            ?.isBlank()
            ?: true

        private fun KtFile.ensureLayoutParamsImport(psiFactory: KtPsiFactory) {
            if (hasLayoutParamsImport()) return

            val importDirective = psiFactory.createImportDirective(ImportPath(FqName(HikageSymbols.HIKAGE_LAYOUT_PARAMS), false))
            importList?.add(importDirective) ?: addAfter(importDirective, packageDirective)
        }

        private fun KtFile.hasLayoutParamsImport() = importDirectives.any { directive ->
            val importedFqName = directive.importedFqName?.asString()
            importedFqName == HikageSymbols.HIKAGE_LAYOUT_PARAMS ||
                directive.isAllUnder && importedFqName == HikageSymbols.HIKAGE_LAYOUT_PACKAGE
        }

        private fun InsertionContext.selectArgument(selectionStart: Int, selectionLength: Int) {
            val selectionEnd = selectionStart + selectionLength
            editor.selectionModel.setSelection(selectionStart, selectionEnd)
            editor.caretModel.moveToOffset(selectionEnd)
        }

        private enum class InsertDefaultLayoutParamsResult {
            INSERTED,
            SKIPPED,
            CALL_NOT_FOUND
        }
    }
}