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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.completion

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.analysis.layout.helper.HikageLayoutTypeHelper
import com.highcapable.hikage.analysis.layout.model.HikageLayout.Id
import com.highcapable.hikage.analysis.layout.model.HikageLayout.Root
import com.highcapable.hikage.generated.PluginProperties
import com.highcapable.hikage.model.AndroidSymbols
import com.highcapable.hikage.model.HikageSymbols
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.utils.extension.addImport
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.impl.TopPriorityLookupElement
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.codeInsight.lookup.WeighingContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import icons.StudioIcons
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Completes statically known Hikage layout IDs and expands typed receiver shortcuts.
 */
class HikageLayoutIdCompletionContributor : CompletionContributor() {

    private companion object {

        const val LAYOUT_ID_PRIORITY = 1_000_000.0
        const val LAYOUT_ID_GROUPING = -1_000_000
        const val LAYOUT_ID_EXPLICIT_PROXIMITY = -1_000_000
        const val ROOT_WEIGHT = 0
        const val ID_WEIGHT = 1
        const val OTHER_WEIGHT = 2
        const val PRIORITY_WEIGHER_ID = "priority"

        val layoutIdLookupKey = Key.create<Int>("${PluginProperties.PROJECT_PLUGIN_ID}.layoutIdLookup")
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC ||
            parameters.position.language != KotlinLanguage.INSTANCE ||
            !ProjectGate.from(parameters.position.project).isEnabled()
        ) {
            super.fillCompletionVariants(parameters, result)
            return
        }

        val resolver = HikageLayoutResolver.from(parameters.position.project)
        parameters.findIdStringContext()?.let { context ->
            val model = resolver.resolve(context.receiver) ?: return
            val matchedResult = result.withPrefixMatcher(context.contentBeforeCaret)
            matchedResult.withLayoutIdSorter(parameters).addAllElements(
                model.ids.map { id -> id.stringLookup(context, parameters.originalFile as? KtFile, resolver) }
            )
            result.stopHere()
            return
        }

        val receiver = parameters.findQualifiedReceiver() ?: return
        val model = resolver.resolve(receiver) ?: return
        val file = parameters.originalFile as? KtFile ?: return
        val layoutResult = result.withLayoutIdSorter(parameters)
        val generatedElements = buildList {
            model.root?.typedLookup(file, resolver)?.let(::add)
            addAll(model.ids.mapNotNull { id -> id.typedLookup(file, resolver) })
        }
        layoutResult.addAllElements(generatedElements)
        result.runRemainingContributors(parameters, false)
            .groupBy { completionResult -> completionResult.prefixMatcher }
            .forEach { (matcher, completionResults) ->
                layoutResult.withPrefixMatcher(matcher).addAllElements(
                    completionResults.map { completionResult -> completionResult.lookupElement }
                )
            }
        result.stopHere()
    }

    private fun Id.stringLookup(stringContext: IdStringContext, file: KtFile?, resolver: HikageLayoutResolver): LookupElement {
        val escapedName = StringUtil.escapeStringCharacters(name)

        return LookupElementBuilder.create(escapedName)
            .withPresentableText(name)
            .withLookupString(name)
            .withIcon(StudioIcons.LayoutEditor.Palette.VIEW)
            .withTypeText(viewClass?.name, true)
            .withInsertHandler { context, _ ->
                var placeholderText = stringContext.owner.placeholderText(name)

                try {
                    context.deleteSuffix(stringContext.contentAfterCaret)
                    val targetFile = file ?: return@withInsertHandler
                    val viewClass = viewClass ?: return@withInsertHandler
                    val type = resolver.createTypeReference(targetFile, viewClass) ?: return@withInsertHandler

                    when (val owner = stringContext.owner) {
                        is IdStringOwner.ArrayAccess -> if (!viewClass.isBaseView() &&
                            context.replaceArrayAccess(owner.expression, escapedName, targetFile, type)
                        ) placeholderText = name
                        is IdStringOwner.GetCall -> owner.typeReference?.let { typeReference ->
                            context.correctTypeArgument(owner.call, typeReference, viewClass, targetFile, type, resolver)
                        }
                    }
                } finally {
                    context.scheduleFoldingCollapse(placeholderText)
                }
            }
            .withLayoutPriority(ID_WEIGHT)
    }

    private fun Id.typedLookup(file: KtFile, resolver: HikageLayoutResolver): LookupElement? {
        val viewClass = viewClass ?: return null
        if (viewClass.isBaseView()) return LookupElementBuilder.create(name)
            .withIcon(StudioIcons.LayoutEditor.Palette.VIEW)
            .withTypeText(viewClass.name, true)
            .withInsertHandler { context, _ ->
                context.replaceSelectorWithArrayAccess(StringUtil.escapeStringCharacters(name))
                context.scheduleFoldingCollapse(".$name")
            }
            .withLayoutPriority(ID_WEIGHT)
        val type = resolver.createTypeReference(file, viewClass) ?: return null

        return LookupElementBuilder.create(name)
            .withIcon(StudioIcons.LayoutEditor.Palette.VIEW)
            .withTypeText(viewClass.name, true)
            .withInsertHandler { context, _ ->
                context.replaceSelector(
                    "${HikageSymbols.HIKAGE_GET_FUNCTION_NAME}<${type.reference}>(\"${StringUtil.escapeStringCharacters(name)}\")",
                    type
                )
                context.scheduleFoldingCollapse(name)
            }
            .withLayoutPriority(ID_WEIGHT)
    }

    private fun Root.typedLookup(file: KtFile, resolver: HikageLayoutResolver): LookupElement? {
        if (viewClass.isBaseView()) return LookupElementBuilder.create(HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME)
            .withIcon(StudioIcons.LayoutEditor.Palette.VIEW)
            .withTypeText(viewClass.name, true)
            .withInsertHandler { context, _ ->
                context.replaceSelector(HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME)
            }
            .withLayoutPriority(ROOT_WEIGHT)
        val type = resolver.createTypeReference(file, viewClass) ?: return null

        return LookupElementBuilder.create(HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME)
            .withIcon(StudioIcons.LayoutEditor.Palette.VIEW)
            .withTypeText(viewClass.name, true)
            .withInsertHandler { context, _ ->
                context.replaceSelector(
                    "${HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME}<${type.reference}>()",
                    type
                )
                context.scheduleFoldingCollapse(HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME)
            }
            .withLayoutPriority(ROOT_WEIGHT)
    }

    private fun CompletionParameters.findQualifiedReceiver(): KtExpression? {
        val reference = PsiTreeUtil.getParentOfType(position, classOf<KtNameReferenceExpression>(), false)
            ?: return null
        val qualified = reference.parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression !== reference || qualified.operationSign != KtTokens.DOT) return null

        return qualified.receiverExpression
    }

    private fun CompletionParameters.findIdStringContext(): IdStringContext? {
        val file = originalFile as? KtFile ?: return null
        val caretOffset = editor.caretModel.offset
        val expression = listOf(caretOffset - 1, caretOffset)
            .filter { offset -> offset >= 0 }
            .firstNotNullOfOrNull { offset ->
                file.findElementAt(offset)?.let { element ->
                    PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
                }
            }
            ?: return null
        val source = expression.text
        if (source.length < 2 || !source.startsWith('"') || !source.endsWith('"') ||
            source.startsWith("\"\"\"") || source.contains('$')
        ) return null

        val contentStart = expression.textRange.startOffset + 1
        val contentEnd = expression.textRange.endOffset - 1
        if (caretOffset !in contentStart..contentEnd) return null
        val content = editor.document.getText(TextRange(contentStart, caretOffset))
        val suffix = editor.document.getText(TextRange(caretOffset, contentEnd))
        if ('\\' in content || '\\' in suffix) return null

        expression.findGetCallOwner()?.let { owner ->
            return IdStringContext(owner, content, suffix)
        }
        expression.findArrayAccessOwner()?.let { owner ->
            return IdStringContext(owner, content, suffix)
        }
        return null
    }

    private fun KtStringTemplateExpression.findGetCallOwner(): IdStringOwner.GetCall? {
        val argument = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return null
        val call = generateSequence(argument.parent) { element -> element.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null

        if (call.calleeExpression?.text != HikageSymbols.HIKAGE_GET_FUNCTION_NAME ||
            call.valueArguments.firstOrNull() !== argument
        ) return null
        val qualified = call.parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression !== call) return null

        return IdStringOwner.GetCall(
            receiver = qualified.receiverExpression,
            call = call,
            typeReference = call.typeArgumentList?.arguments?.singleOrNull()?.typeReference
        )
    }

    private fun KtStringTemplateExpression.findArrayAccessOwner(): IdStringOwner.ArrayAccess? {
        val arrayAccess = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtArrayAccessExpression>()
            .firstOrNull { access -> access.indexExpressions.singleOrNull() === this }
            ?: return null
        val receiver = arrayAccess.arrayExpression ?: return null

        return IdStringOwner.ArrayAccess(receiver, arrayAccess)
    }

    private fun InsertionContext.replaceSelector(replacement: String) {
        setAddCompletionChar(false)
        document.replaceString(startOffset, tailOffset, replacement)
        tailOffset = startOffset + replacement.length
        commitDocument()
    }

    private fun InsertionContext.replaceSelector(replacement: String, type: HikageLayoutTypeHelper.Reference) {
        replaceSelector(replacement)
        val file = file as? KtFile ?: return
        moveCaretAfterImports(tailOffset) {
            type.importFqName?.let { fqName -> file.addImport(KtPsiFactory(project), fqName) }
        }
    }

    private fun InsertionContext.replaceSelectorWithArrayAccess(escapedName: String) {
        val selectorStart = startOffset
        val dotOffset = selectorStart - 1
        if (dotOffset < 0 || document.charsSequence[dotOffset] != '.') return
        val replacement = "[\"$escapedName\"]"

        setAddCompletionChar(false)
        document.replaceString(dotOffset, tailOffset, replacement)
        tailOffset = dotOffset + replacement.length
        commitDocument()
    }

    private fun InsertionContext.replaceArrayAccess(
        arrayAccess: KtArrayAccessExpression,
        escapedName: String,
        file: KtFile,
        type: HikageLayoutTypeHelper.Reference
    ): Boolean {
        setAddCompletionChar(false)
        commitDocument()

        if (!arrayAccess.isValid) return false
        val receiver = arrayAccess.arrayExpression ?: return false

        val replacement = KtPsiFactory(project).createExpression(
            "${receiver.text}.${HikageSymbols.HIKAGE_GET_FUNCTION_NAME}<${type.reference}>(\"$escapedName\")"
        )
        val replaced = arrayAccess.replace(replacement)

        commitDocument()
        moveCaretAfterImports(replaced.textRange.endOffset) {
            type.importFqName?.let { fqName -> file.addImport(KtPsiFactory(project), fqName) }
        }

        return true
    }

    private fun InsertionContext.correctTypeArgument(
        call: KtCallExpression,
        typeReference: KtTypeReference,
        viewClass: PsiClass,
        file: KtFile,
        type: HikageLayoutTypeHelper.Reference,
        resolver: HikageLayoutResolver
    ) {
        commitDocument()
        if (!call.isValid || !typeReference.isValid) return

        val currentClass = resolver.resolveTypeClass(typeReference)
        val expectedQualifiedName = viewClass.qualifiedName
        if (currentClass == viewClass ||
            expectedQualifiedName != null && currentClass?.qualifiedName == expectedQualifiedName ||
            typeReference.text == type.reference
        ) return

        val typeArgumentList = call.typeArgumentList ?: return
        val replacement = (KtPsiFactory(project)
            .createExpression("get<${type.reference}>()") as? KtCallExpression)
            ?.typeArgumentList
            ?: return

        val marker = document.createRangeMarker(tailOffset, tailOffset)
        try {
            typeArgumentList.replace(replacement)
            commitDocument()
            type.importFqName?.let { fqName -> file.addImport(KtPsiFactory(project), fqName) }
            commitDocument()
            if (marker.isValid) editor.caretModel.moveToOffset(marker.startOffset)
        } finally {
            marker.dispose()
        }
    }

    private inline fun InsertionContext.moveCaretAfterImports(offset: Int, addImports: () -> Unit) {
        val marker = document.createRangeMarker(offset, offset)
        try {
            addImports()
            commitDocument()
            if (marker.isValid) editor.caretModel.moveToOffset(marker.startOffset)
        } finally {
            marker.dispose()
        }
    }

    private fun InsertionContext.deleteSuffix(suffix: String) {
        if (suffix.isEmpty()) return

        val endOffset = tailOffset + suffix.length
        if (endOffset <= document.textLength &&
            document.charsSequence.subSequence(tailOffset, endOffset).toString() == suffix
        ) document.deleteString(tailOffset, endOffset)
    }

    private fun InsertionContext.scheduleFoldingCollapse(placeholderText: String) {
        commitDocument()
        val marker = document.createRangeMarker(editor.caretModel.offset, editor.caretModel.offset)
        val foldingManager = CodeFoldingManager.getInstance(project)
        ReadAction.nonBlocking<Runnable?> {
            if (project.isDisposed || editor.isDisposed || !marker.isValid) null
            else foldingManager.updateFoldRegionsAsync(editor, false)
        }
            .withDocumentsCommitted(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { update ->
                try {
                    if (project.isDisposed || editor.isDisposed || !marker.isValid) return@finishOnUiThread
                    update?.run() ?: return@finishOnUiThread
                    val offset = marker.startOffset
                    val region = editor.foldingModel.allFoldRegions
                        .asSequence()
                        .filter { region ->
                            region.isValid && region.placeholderText == placeholderText &&
                                foldingManager.isCollapsedByDefault(region) == true &&
                                region.startOffset <= offset && offset <= region.endOffset
                        }
                        .minByOrNull { region -> region.endOffset - region.startOffset }
                        ?: return@finishOnUiThread
                    editor.foldingModel.runBatchFoldingOperation { region.isExpanded = false }
                } finally {
                    marker.dispose()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { marker.dispose() }
    }

    private fun PsiClass.isBaseView() = qualifiedName == AndroidSymbols.VIEW_CLASS

    private fun CompletionResultSet.withLayoutIdSorter(parameters: CompletionParameters): CompletionResultSet {
        val sorter = CompletionSorter.defaultSorter(parameters, prefixMatcher)
            .weighBefore(PRIORITY_WEIGHER_ID, LayoutIdLookupWeigher())
        return withRelevanceSorter(sorter)
    }

    private fun LookupElement.withLayoutPriority(weight: Int): LookupElement {
        val prioritized = PrioritizedLookupElement
            .withPriority(this, LAYOUT_ID_PRIORITY)
            .let { element -> PrioritizedLookupElement.withGrouping(element, LAYOUT_ID_GROUPING) }
            .let { element -> PrioritizedLookupElement.withExplicitProximity(element, LAYOUT_ID_EXPLICIT_PROXIMITY) }

        return TopPriorityLookupElement.prioritizeToTop(prioritized, false).also { element ->
            element.putUserData(layoutIdLookupKey, weight)
        }
    }

    private data class IdStringContext(
        val owner: IdStringOwner,
        val contentBeforeCaret: String,
        val contentAfterCaret: String
    ) {

        val receiver get() = owner.receiver
    }

    private sealed interface IdStringOwner {

        val receiver: KtExpression

        data class ArrayAccess(
            override val receiver: KtExpression,
            val expression: KtArrayAccessExpression
        ) : IdStringOwner

        data class GetCall(
            override val receiver: KtExpression,
            val call: KtCallExpression,
            val typeReference: KtTypeReference?
        ) : IdStringOwner

        fun placeholderText(name: String) = if (this is ArrayAccess) ".$name" else name
    }

    private class LayoutIdLookupWeigher : LookupElementWeigher("hikageLayoutId") {

        override fun weigh(element: LookupElement, context: WeighingContext) = element.getUserData(layoutIdLookupKey) ?: OTHER_WEIGHT
    }
}