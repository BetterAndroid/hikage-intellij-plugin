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
 * This file is created by fankes on 2026/8/2.
 */
package com.highcapable.hikage.convert.output

import com.android.tools.idea.projectsystem.getModuleSystem
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.base.codeInsight.copyPaste.RestoreReferencesDialog
import org.jetbrains.kotlin.idea.base.codeInsight.copyPaste.ReviewAddedImports
import org.jetbrains.kotlin.idea.base.psi.imports.addImport
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

/**
 * Restores a generated Kotlin snippet against the actual paste target without analyzing a synthetic source file.
 */
class KotlinSnippetPasteProcessor : CopyPastePostProcessor<KotlinSnippetPasteProcessor.TransferableData>() {

    /**
     * Clipboard metadata for a generated Kotlin snippet.
     * @param imports the exact non-wildcard imports required by the plain-text snippet.
     * @param unqualifiedResourceClassName the exact non-framework `R` class rendered as the unqualified `R`, or null.
     */
    data class TransferableData(
        val imports: List<String>,
        val unqualifiedResourceClassName: String? = null
    ) : TextBlockTransferableData {

        companion object {

            /** The JVM-local clipboard flavor owned by the Hikage Kotlin snippet paste processor. */
            val dataFlavor by lazy {
                val dataClass = classOf<TransferableData>()
                DataFlavor(
                    "application/x-java-jvm-local-objectref;class=${dataClass.name}",
                    dataClass.simpleName,
                    dataClass.classLoader
                )
            }
        }

        override fun getFlavor() = dataFlavor
    }

    override fun collectTransferableData(file: PsiFile, editor: Editor, startOffsets: IntArray, endOffsets: IntArray) = emptyList<TransferableData>()

    override fun extractTransferableData(content: Transferable) = try {
        listOfNotNull(content.getTransferData(TransferableData.dataFlavor) as? TransferableData)
    } catch (_: UnsupportedFlavorException) {
        emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<TransferableData>
    ) {
        val documentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitDocument(editor.document)
        var targetFile = documentManager.getPsiFile(editor.document) as? KtFile ?: return
        if (!targetFile.isValid) return

        val settings = CodeInsightSettings.getInstance()
        val unqualifiedResourceClassName = values.mapNotNull(TransferableData::unqualifiedResourceClassName)
            .distinct()
            .singleOrNull()
        val canImportResourceClass = unqualifiedResourceClassName != null &&
            unqualifiedResourceClassName == targetFile.androidResourceClassName() &&
            targetFile.canImportDirectly(unqualifiedResourceClassName)

        val requestedImports = values.asSequence()
            .flatMap { data -> data.imports.asSequence() }
            .filter { importName -> importName.contains('.') && !importName.endsWith(".*") }
            .filter { importName -> importName != unqualifiedResourceClassName || canImportResourceClass }
            .distinct()
            .sorted()
            .toList()
        val selectedImports = when {
            settings.ADD_IMPORTS_ON_PASTE == CodeInsightSettings.NO || requestedImports.isEmpty() -> emptyList()
            settings.ADD_IMPORTS_ON_PASTE == CodeInsightSettings.ASK -> {
                val dialog = RestoreReferencesDialog(project, requestedImports.toTypedArray())
                dialog.show()
                dialog.selectedElements.filterIsInstance<String>()
            }
            else -> requestedImports
        }

        if (unqualifiedResourceClassName != null && unqualifiedResourceClassName !in selectedImports) {
            targetFile.qualifyResourceReferences(project, bounds, unqualifiedResourceClassName)
            documentManager.doPostponedOperationsAndUnblockDocument(editor.document)
            documentManager.commitDocument(editor.document)
            targetFile = documentManager.getPsiFile(editor.document) as? KtFile ?: return
            if (!targetFile.isValid) return
        }
        if (selectedImports.isEmpty()) return

        val importsToAdd = selectedImports.filterNot { importName ->
            targetFile.alreadyImports(importName)
        }
        if (importsToAdd.isEmpty()) return

        WriteAction.run<RuntimeException> {
            importsToAdd.forEach { importName ->
                targetFile.addImport(FqName(importName))
            }
        }
        ReviewAddedImports.reviewAddedImports(project, editor, targetFile, importsToAdd)
    }

    private fun KtFile.androidResourceClassName() = AndroidFacet.getInstance(this)
        ?.getModuleSystem()?.getPackageName()
        ?.takeIf(String::isNotBlank)
        ?.let { packageName -> "$packageName.R" }

    private fun KtFile.canImportDirectly(importName: String): Boolean {
        if (alreadyImports(importName)) return true
        val shortName = importName.substringAfterLast('.')
        return importDirectives.none { directive ->
            directive.aliasName == null && !directive.isAllUnder &&
                directive.importedFqName?.asString()?.substringAfterLast('.') == shortName
        }
    }

    private fun KtFile.qualifyResourceReferences(
        project: Project,
        bounds: RangeMarker,
        resourceClassName: String
    ) {
        if (!bounds.isValid) return
        val references = PsiTreeUtil.findChildrenOfType(this, classOf<KtNameReferenceExpression>())
            .filter { reference ->
                reference.text == "R" && reference.textRange.startOffset >= bounds.startOffset &&
                    reference.textRange.endOffset <= bounds.endOffset &&
                    (reference.parent as? KtDotQualifiedExpression)?.receiverExpression == reference
            }
            .sortedByDescending { reference -> reference.textRange.startOffset }
        if (references.isEmpty()) return

        val psiFactory = KtPsiFactory(project)
        WriteAction.run<RuntimeException> {
            references.forEach { reference ->
                if (reference.isValid) reference.replace(psiFactory.createExpression(resourceClassName))
            }
        }
    }

    private fun KtFile.alreadyImports(importName: String): Boolean {
        val packageName = importName.substringBeforeLast('.')

        return packageFqName.asString() == packageName || importDirectives.any { directive ->
            val importedName = directive.importedFqName?.asString()
            importedName == importName && directive.aliasName == null ||
                directive.isAllUnder && importedName == packageName
        }
    }
}