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
package com.highcapable.hikage.intellij.refactoring

import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.intellij.dsl.resolve.PerformerDeclarations
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Provides support for renaming Hikage views and their associated generated performers.
 */
internal object HikageViewRenameSupport {

    private const val ALIAS_FIELD = "alias"
    private const val ALIAS_POSITION = 1

    /**
     * Links an active generated performer declaration back to its project View source.
     */
    data class PerformerLink(
        val view: KtClassOrObject,
        val declaration: PerformerDeclaration
    )

    /**
     * Identifies a performer independently of whether its declaration snapshot is currently available.
     */
    data class PerformerReference(
        val generatedKey: String,
        val generatedPackageName: String,
        val functionName: String
    )

    /**
     * Defers a same-file View rename until after the declaration PSI has been updated.
     */
    data class FileRename(
        val file: KtFile,
        val newFileName: String
    ) {

        fun rename() {
            if (file.isValid) file.setName(newFileName)
        }
    }

    /**
     * Captures the generated imports and unaliased calls that must follow a View rename.
     */
    data class References(
        val imports: List<KtImportDirective>,
        val calls: List<KtNameReferenceExpression>
    ) {

        fun rename(performer: PerformerLink, newFunctionName: String) {
            val newGeneratedKey = "${performer.declaration.generatedPackageName}.$newFunctionName"
            val psiFactory = KtPsiFactory(performer.view.project)

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

    /**
     * Resolves a PSI element to a project-local `@HikageView` declaration.
     *
     * Generated and dependency classes must not participate because their generated performer is not owned by this project.
     */
    fun findProjectHikageView(element: PsiElement): KtClassOrObject? {
        val declaration = element as? KtClassOrObject
            ?: element.navigationElement as? KtClassOrObject
            ?: element.findParentOfType<KtClassOrObject>()
            ?: return null
        val virtualFile = declaration.containingFile.virtualFile ?: return null
        if (!ProjectFileIndex.getInstance(declaration.project).isInContent(virtualFile)) return null

        return declaration.takeIf { it.findHikageViewAnnotation() != null }
    }

    /** Matches a generated function PSI element to the current performer declaration snapshot. */
    fun findGeneratedPerformer(element: PsiElement): PerformerDeclaration? {
        val function = element as? KtNamedFunction ?: element.navigationElement as? KtNamedFunction ?: return null
        val functionName = function.name ?: return null
        val packageName = function.containingKtFile.packageFqName.asString()
        val generatedKey = "$packageName.$functionName"

        return PerformerDeclarations.resolve(function.project).singleOrNull { declaration ->
            declaration.generatedKey == generatedKey
        }
    }

    /** Finds the annotation-derived performer associated with a View declaration. */
    fun findGeneratedPerformer(view: KtClassOrObject): PerformerDeclaration? {
        val viewClass = view.fqName?.asString() ?: return null
        return PerformerDeclarations.resolve(view.project).firstOrNull { declaration ->
            declaration.source == Source.ANNOTATION && declaration.viewClass == viewClass
        }
    }

    /**
     * Obtains the performer key used to filter ordinary class usages during rename.
     *
     * The explicit alias fallback keeps alias imports protected while the declaration snapshot is temporarily unavailable.
     */
    fun findPerformerReference(view: KtClassOrObject) = findGeneratedPerformer(view)
        ?.toPerformerReference()
        ?: view.explicitAliasName()?.let { alias ->
            val packageName = view.containingKtFile.packageFqName.asString()
            val generatedPackageName = listOf(HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX, packageName)
                .filter(String::isNotBlank)
                .joinToString(".")
            PerformerReference("$generatedPackageName.$alias", generatedPackageName, alias)
        }

    /** Returns only performers that are safe to redirect to their source View declaration. */
    fun findRenamablePerformer(element: PsiElement): PerformerLink? {
        val performer = findGeneratedPerformer(element) ?: return null
        if (performer.source != Source.ANNOTATION) return null

        return performer.findProjectHikageView(element.project)?.takeUnless { view -> view.hasExplicitAlias() }?.let { view ->
            PerformerLink(view, performer)
        }
    }

    /** The View-side equivalent of [findRenamablePerformer], used when renaming the source declaration. */
    fun findRenamablePerformer(view: KtClassOrObject) = findGeneratedPerformer(view)?.takeUnless {
        view.hasExplicitAlias()
    }?.let { performer ->
        PerformerLink(view, performer)
    }

    /**
     * Reads the leaf PSI at the editor caret instead of trusting the Rename data-context target.
     *
     * Kotlin may resolve an import segment to its generated function before the rename handler runs.
     */
    fun findCaretElement(dataContext: DataContext): PsiElement? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val project = editor.project ?: return null

        return PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            ?.findElementAt(editor.caretModel.offset)
    }

    /** Recognizes imports from the generated performer package, which are immutable implementation details. */
    fun findHikageWidgetImport(element: PsiElement?) = element
        ?.findParentOfType<KtImportDirective>()
        ?.takeIf { directive ->
            directive.importedFqName?.asString().let { importedFqName ->
                importedFqName == HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX ||
                    importedFqName?.startsWith("${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.") == true
            }
        }

    /** Mirrors KSP's nested-class performer name convention: `Outer.Inner` becomes `Outer_Inner`. */
    fun generatedFunctionName(view: KtClassOrObject): String? {
        val classFqName = view.fqName?.asString() ?: return null
        val packageName = view.containingKtFile.packageFqName.asString()

        return classFqName.removePrefix("$packageName.").replace(".", "_")
    }

    /** Keeps a top-level View's file name aligned when the file was named after the View before refactoring. */
    fun findFileRename(view: KtClassOrObject, newName: String): FileRename? {
        if (view.parent !is KtFile) return null
        val className = view.name ?: return null
        val file = view.containingKtFile
        val extension = file.name.substringAfterLast(".", "")
        val fileNameWithoutExtension = file.name.removeSuffix(".$extension")
        if (fileNameWithoutExtension != className) return null

        val newFileName = if (extension.isBlank()) newName else "$newName.$extension"
        return FileRename(file, newFileName)
    }

    /**
     * Identifies generated performer usages that must be excluded from the platform's ordinary class rename.
     *
     * Direct imports and all-under imports are both supported because they expose different call-site names.
     */
    fun isPerformerReference(reference: PsiReference, performer: PerformerReference): Boolean {
        val element = reference.element
        val importDirective = element.findParentOfType<KtImportDirective>()
        if (importDirective != null && importDirective.isPerformerImport(performer)) return true

        val call = element.findParentOfType<KtCallExpression>() ?: return false
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.getReferencedName() in callee.containingKtFile.importedPerformerNames(performer)
    }

    /**
     * Collects the generated imports and only those calls whose unaliased or all-under import exposes KSP's generated name.
     *
     * Aliased calls intentionally remain unchanged because the alias is preserved when its import target is replaced.
     */
    fun collectReferences(performer: PerformerLink): References {
        val imports = mutableListOf<KtImportDirective>()
        val calls = mutableListOf<KtNameReferenceExpression>()
        val searchScope = GlobalSearchScope.projectScope(performer.view.project)

        PsiSearchHelper.getInstance(performer.view.project).processAllFilesWithWord(
            performer.declaration.functionName,
            searchScope,
            { file ->
                val ktFile = file as? KtFile ?: return@processAllFilesWithWord true
                val directImports = ktFile.importDirectives.filter { directive ->
                    directive.importedFqName?.asString() == performer.declaration.generatedKey
                }
                imports += directImports

                val callableNames = buildSet {
                    if (directImports.any { directive -> directive.aliasName == null }) add(performer.declaration.functionName)
                    if (ktFile.importDirectives.any { directive ->
                            directive.isAllUnder && directive.importedFqName?.asString() == performer.declaration.generatedPackageName
                        }) add(performer.declaration.functionName)
                }
                if (callableNames.isEmpty()) return@processAllFilesWithWord true

                calls += ktFile.collectDescendantsOfType<KtCallExpression>()
                    .mapNotNull { call -> call.calleeExpression as? KtNameReferenceExpression }
                    .filter { callee -> callee.getReferencedName() in callableNames }
                true
            },
            true
        )
        return References(imports, calls)
    }

    private fun PerformerDeclaration.findProjectHikageView(project: Project) = JavaPsiFacade.getInstance(project)
        .findClass(viewClass, GlobalSearchScope.projectScope(project))
        ?.navigationElement
        ?.let(::findProjectHikageView)

    private fun KtClassOrObject.findHikageViewAnnotation() = annotationEntries.firstOrNull { annotation ->
        DeclarationMatcher.isHikageAnnotation(annotation, HikageSymbols.HIKAGE_VIEW_ANNOTATION)
    }

    private fun KtClassOrObject.hasExplicitAlias() = explicitAliasName() != null

    private fun KtClassOrObject.explicitAliasName() = findHikageViewAnnotation()
        ?.explicitAliasExpression()
        ?.text
        ?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank)

    private fun KtAnnotationEntry.explicitAliasExpression() = valueArgumentList?.arguments
        ?.let { arguments ->
            arguments.firstOrNull { argument -> argument.getArgumentName()?.asName?.identifier == ALIAS_FIELD }
                ?: arguments.getOrNull(ALIAS_POSITION)?.takeIf { argument -> argument.getArgumentName() == null }
        }?.getArgumentExpression()

    private fun PerformerDeclaration.toPerformerReference() = PerformerReference(
        generatedKey, generatedPackageName, functionName
    )

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