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
package com.highcapable.hikage.refactoring.view

import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.refactoring.view.model.HikageViewRenameTarget
import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Keeps an unaliased project `@HikageView` declaration and its generated performer in sync during rename refactorings.
 */
class HikageViewRenameProcessor : RenamePsiElementProcessor() {

    private data class PerformerReference(
        val generatedKey: String,
        val generatedPackageName: String,
        val functionName: String
    )

    private data class PendingFileRename(
        val file: KtFile,
        val newFileName: String
    ) {

        fun rename() {
            if (file.isValid) file.setName(newFileName)
        }
    }

    private data class PerformerReferences(
        val imports: List<KtImportDirective>,
        val calls: List<KtNameReferenceExpression>
    ) {

        fun rename(target: HikageViewRenameTarget, newFunctionName: String) {
            val newGeneratedKey = "${target.declaration.generatedPackageName}.$newFunctionName"
            val psiFactory = KtPsiFactory(target.view.project)

            // Aliased imports keep their local call name; only the imported target changes.
            imports.filter(PsiElement::isValid).forEach { directive ->
                val alias = directive.aliasName?.let(Name::identifier)
                directive.replace(psiFactory.createImportDirective(ImportPath(FqName(newGeneratedKey), false, alias)))
            }
            calls.filter(PsiElement::isValid).forEach { call ->
                call.replace(psiFactory.createExpression(newFunctionName))
            }
        }
    }

    override fun createRenameDialog(
        project: Project,
        element: PsiElement,
        nameSuggestionContext: PsiElement?,
        editor: Editor?
    ) = object : RenameDialog(project, element, nameSuggestionContext, editor) {
        override fun createRenameProcessor(newName: String) = HikageViewRenameRefactoringProcessor(
            project,
            element,
            newName,
            refactoringScope,
            isSearchInComments,
            isSearchInNonJavaFiles
        )
    }

    override fun canProcessElement(element: PsiElement): Boolean {
        if (!ProjectGate.from(element.project).isEnabled()) return false
        val view = HikageViewRenameTargetResolver.findProjectView(element)
        val target = HikageViewRenameTargetResolver.findRenamableTarget(element)

        return view != null || target != null
    }

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement {
        val view = HikageViewRenameTargetResolver.findProjectView(element)
        val target = HikageViewRenameTargetResolver.findRenamableTarget(element)

        return target?.view ?: view ?: element
    }

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val references = super.findReferences(element, searchScope, searchInCommentsAndStrings)
        val view = HikageViewRenameTargetResolver.findProjectView(element)
        val performer = view?.let(::findPerformerReference) ?: return references

        // Generated performer imports and calls are renamed separately so nested class names follow KSP's `Outer_Inner` convention.
        return references.filterNot { reference -> isPerformerReference(reference, performer) }
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val view = HikageViewRenameTargetResolver.findProjectView(element)
        val target = view?.let(HikageViewRenameTargetResolver::findRenamableTarget)
        val references = target?.let(::collectReferences)
        val fileRename = view?.let { declaredView -> findFileRename(declaredView, newName) }

        super.renameElement(element, newName, usages, listener)

        if (view != null && target != null && references != null) {
            val generatedFunctionName = generatedFunctionName(view)
            if (generatedFunctionName != null) references.rename(target, generatedFunctionName)
        }
        fileRename?.rename()
    }

    private fun findPerformerReference(view: KtClassOrObject) = HikageViewRenameTargetResolver.findGeneratedPerformer(view)
        ?.let { declaration ->
            PerformerReference(declaration.generatedKey, declaration.generatedPackageName, declaration.functionName)
        }
        ?: HikageViewRenameTargetResolver.findExplicitAlias(view)?.let { alias ->
            val packageName = view.containingKtFile.packageFqName.asString()
            val generatedPackageName = listOf(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX, packageName)
                .filter(String::isNotBlank)
                .joinToString(".")
            PerformerReference("$generatedPackageName.$alias", generatedPackageName, alias)
        }

    private fun isPerformerReference(reference: PsiReference, performer: PerformerReference): Boolean {
        val element = reference.element
        val importDirective = element.findParentOfType<KtImportDirective>()
        if (importDirective != null && importDirective.isPerformerImport(performer)) return true

        val call = element.findParentOfType<KtCallExpression>() ?: return false
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.getReferencedName() in callee.containingKtFile.importedPerformerNames(performer)
    }

    private fun collectReferences(target: HikageViewRenameTarget): PerformerReferences {
        val imports = mutableListOf<KtImportDirective>()
        val calls = mutableListOf<KtNameReferenceExpression>()
        val searchScope = GlobalSearchScope.projectScope(target.view.project)

        PsiSearchHelper.getInstance(target.view.project).processAllFilesWithWord(
            target.declaration.functionName,
            searchScope,
            { file ->
                val ktFile = file as? KtFile ?: return@processAllFilesWithWord true
                val directImports = ktFile.importDirectives.filter { directive ->
                    directive.importedFqName?.asString() == target.declaration.generatedKey
                }
                imports += directImports

                val callableNames = buildSet {
                    if (directImports.any { directive -> directive.aliasName == null }) add(target.declaration.functionName)
                    if (ktFile.importDirectives.any { directive ->
                            directive.isAllUnder && directive.importedFqName?.asString() == target.declaration.generatedPackageName
                        }) add(target.declaration.functionName)
                }
                if (callableNames.isEmpty()) return@processAllFilesWithWord true

                calls += ktFile.collectDescendantsOfType<KtCallExpression>()
                    .mapNotNull { call -> call.calleeExpression as? KtNameReferenceExpression }
                    .filter { callee -> callee.getReferencedName() in callableNames }
                true
            },
            true
        )
        return PerformerReferences(imports, calls)
    }

    private fun findFileRename(view: KtClassOrObject, newName: String): PendingFileRename? {
        if (view.parent !is KtFile) return null
        val className = view.name ?: return null
        val file = view.containingKtFile
        val extension = file.name.substringAfterLast(".", "")
        val fileNameWithoutExtension = file.name.removeSuffix(".$extension")
        if (fileNameWithoutExtension != className) return null

        val newFileName = if (extension.isBlank()) newName else "$newName.$extension"
        return PendingFileRename(file, newFileName)
    }

    private fun generatedFunctionName(view: KtClassOrObject): String? {
        val classFqName = view.fqName?.asString() ?: return null
        val packageName = view.containingKtFile.packageFqName.asString()

        return classFqName.removePrefix("$packageName.").replace(".", "_")
    }

    private fun KtFile.importedPerformerNames(performer: PerformerReference) = buildSet {
        importDirectives.forEach { directive ->
            val importedFqName = directive.importedFqName?.asString() ?: return@forEach
            when {
                importedFqName == performer.generatedKey -> add(directive.aliasName ?: performer.functionName)
                directive.isAllUnder && importedFqName == performer.generatedPackageName -> add(performer.functionName)
            }
        }
    }

    private fun KtImportDirective.isPerformerImport(performer: PerformerReference): Boolean {
        val importedFqName = importedFqName?.asString() ?: return false
        return importedFqName == performer.generatedKey ||
            isAllUnder && importedFqName == performer.generatedPackageName
    }

    private inline fun <reified T : PsiElement> PsiElement.findParentOfType() = generateSequence(this) { element -> element.parent }
        .filterIsInstance<T>()
        .firstOrNull()
}