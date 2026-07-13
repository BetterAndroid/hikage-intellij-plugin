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
 * This file is created by fankes on 2026/7/14.
 */
package com.highcapable.hikage.intellij.dsl.resolve

import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.intellij.dsl.model.PerformerSpec
import com.highcapable.hikage.intellij.dsl.model.ViewDeclaration
import com.highcapable.hikage.intellij.dsl.model.ViewDeclarationFileItem
import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.model.SystemSymbols
import com.highcapable.hikage.intellij.project.model.ProjectModels
import com.highcapable.hikage.intellij.utils.extension.isNullable
import com.highcapable.hikage.intellij.utils.extension.isTypeOf
import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import javax.lang.model.SourceVersion

/**
 * Collects the declarations that Hikage KSP would turn into performer functions.
 */
class PerformerDeclarationCollector(private val project: Project) {

    private companion object {

        const val LPARAMS_FIELD = "lparams"
        const val ALIAS_FIELD = "alias"
        const val ATTRS_FIELD = "attrs"
        const val INIT_FIELD = "init"
        const val PERFORMER_FIELD = "performer"
        const val VIEW_FIELD = "view"

        const val VIEW_DECLARATION_DIRECTORY = "hikage-view-declaration"
        const val PACKAGED_VIEW_DECLARATION_DIRECTORY = "META-INF/hikage/view-declaration"
        const val GENERATED_VIEW_DECLARATION_DIRECTORY = "build/generated/hikage/view-declaration-files"
        const val KSP_GENERATED_SOURCE_PATH = "generated/ksp"
        const val JSON_FILE_EXTENSION = "json"

        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
            "var", "when", "while", "by", "catch", "constructor", "delegate",
            "dynamic", "field", "file", "finally", "get", "import", "init", "param",
            "property", "receiver", "set", "setparam", "where", "actual", "abstract",
            "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
            "external", "final", "infix", "inline", "inner", "internal", "lateinit",
            "noinline", "open", "operator", "out", "override", "private", "protected",
            "public", "reified", "sealed", "suspend", "tailrec", "value", "vararg", "_"
        )
    }

    private val javaFacade get() = JavaPsiFacade.getInstance(project)
    private val searchScope get() = GlobalSearchScope.allScope(project)
    private val annotationSearchScope get() = GlobalSearchScope.projectScope(project)

    /**
     * Returns the deterministic, conflict-free performer declarations available to K2.
     */
    fun collect(): List<PerformerDeclaration> {
        val annotationPerformers = collectAnnotatedDeclarations(HikageSymbols.HIKAGE_VIEW_ANNOTATION) +
            collectAnnotatedDeclarations(HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION)
        val annotationViewClasses = annotationPerformers.mapTo(mutableSetOf()) { declaration -> declaration.viewClass }

        val strictFilePerformers = collectViewDeclarationFiles(Source.STRICT_FILE)
            .filter { declaration -> declaration.viewClass !in annotationViewClasses }
        val strictViewClasses = annotationViewClasses + strictFilePerformers.map(PerformerDeclaration::viewClass)
        val optionalFilePerformers = collectViewDeclarationFiles(Source.OPTIONAL_FILE)
            .filter { declaration -> declaration.viewClass !in strictViewClasses }

        return (annotationPerformers + strictFilePerformers + optionalFilePerformers)
            .filterNot(::shouldSkipExistingHikagableFunction)
            .withoutDuplicateGeneratedKeys()
            .sortedWith(compareBy(PerformerDeclaration::generatedPackageName, PerformerDeclaration::functionName))
    }

    private fun collectAnnotatedDeclarations(annotationFqName: String): List<PerformerDeclaration> {
        val annotationName = annotationFqName.substringAfterLast(".")
        return collectKtFilesContaining(annotationName)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .mapNotNull { declaration -> declaration.toAnnotatedPerformerDeclaration(annotationFqName) }
            .toList()
    }

    private fun collectKtFilesContaining(word: String): List<KtFile> {
        val files = linkedSetOf<KtFile>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, annotationSearchScope, { file ->
            if (file is KtFile) files += file
            true
        }, true)
        return files.toList()
    }

    private fun collectViewDeclarationFiles(source: Source): List<PerformerDeclaration> {
        val indexedFiles = when (source) {
            Source.STRICT_FILE -> FilenameIndex.getAllFilesByExt(project, JSON_FILE_EXTENSION, annotationSearchScope)
                .filter { file -> file.isInLocalFileSystem && file.declarationSource() == Source.STRICT_FILE }
            Source.OPTIONAL_FILE, Source.ANNOTATION -> emptyList()
        }
        val generatedFiles = collectGeneratedViewDeclarationFiles(source)
        return (indexedFiles + generatedFiles)
            .distinctBy { file -> file.url }
            .flatMap { file -> file.toViewDeclarationFileItems(source) }
    }

    private fun collectGeneratedViewDeclarationFiles(source: Source): List<VirtualFile> {
        val directoryName = when (source) {
            Source.STRICT_FILE -> "strict"
            Source.OPTIONAL_FILE -> "optional"
            Source.ANNOTATION -> return emptyList()
        }
        return ProjectRootManager.getInstance(project).contentRoots.asSequence()
            .mapNotNull { root -> root.findFileByRelativePath("$GENERATED_VIEW_DECLARATION_DIRECTORY/$directoryName") }
            .flatMap { directory -> directory.collectJsonFiles().asSequence() }
            .toList()
    }

    private fun VirtualFile.collectJsonFiles(): List<VirtualFile> = buildList {
        VfsUtilCore.iterateChildrenRecursively(
            this@collectJsonFiles,
            { file -> file.isDirectory || file.extension == JSON_FILE_EXTENSION }
        ) { file ->
            if (!file.isDirectory) add(file)
            true
        }
    }

    private fun VirtualFile.toViewDeclarationFileItems(source: Source): List<PerformerDeclaration> {
        val text = VfsUtilCore.loadText(this)
        return try {
            Json.decodeFromString<List<ViewDeclarationFileItem>>(text)
                .mapNotNull { item -> item.toPerformerDeclaration(source, this) }
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    private fun ViewDeclarationFileItem.toPerformerDeclaration(source: Source, originFile: VirtualFile): PerformerDeclaration? {
        val viewClass = viewClass.trim().takeIf(String::isNotEmpty) ?: return null
        val lparams = lparams?.trim()?.takeIf(String::isNotEmpty)
        val declaration = viewClass.toFileViewDeclaration(alias, lparams != null) ?: return null
        val spec = PerformerSpec(
            lparams = lparams,
            attrs = attrs,
            init = init,
            performer = performer
        )
        return declaration.toPerformerDeclaration(spec, source, originFile)
    }

    private fun KtClassOrObject.toAnnotatedPerformerDeclaration(annotationFqName: String): PerformerDeclaration? {
        val annotation = findAnnotation(annotationFqName) ?: return null
        if (annotationFqName == HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION &&
            (this !is KtObjectDeclaration || isCompanion())
        ) return null

        val viewClass = when (annotationFqName) {
            HikageSymbols.HIKAGE_VIEW_ANNOTATION -> ownClassFqName()
            else -> annotation.classLiteralAttribute(VIEW_FIELD)
        } ?: return null
        val isViewGroup = when (annotationFqName) {
            HikageSymbols.HIKAGE_VIEW_ANNOTATION -> {
                if (!isValidHikageViewClass()) return null
                hasViewGroupSuperTypeHint()
            }
            else -> {
                if (!viewClass.isValidAnnotatedViewDeclaration()) return null
                viewClass.isAndroidViewGroupClassNameWithoutAnalysis()
            }
        }
        val declaration = viewClass.toViewDeclaration(annotation.stringAttribute(ALIAS_FIELD), isViewGroup) ?: return null
        val spec = PerformerSpec(
            lparams = annotation.classLiteralAttribute(LPARAMS_FIELD),
            attrs = annotation.booleanAttribute(ATTRS_FIELD, true),
            init = annotation.booleanAttribute(INIT_FIELD, true),
            performer = annotation.booleanAttribute(PERFORMER_FIELD, true)
        )
        return declaration.toPerformerDeclaration(spec, Source.ANNOTATION, containingKtFile.virtualFile)
    }

    private fun String.toFileViewDeclaration(alias: String?, hasDeclaredLparams: Boolean): ViewDeclaration? {
        if (this == SystemSymbols.KOTLIN_ANY || this == SystemSymbols.JAVA_LANG_OBJECT) return null
        findProjectKtClass(this)?.let { ktClass ->
            if (!ktClass.isValidHikageViewClass()) return null
            return toViewDeclaration(alias, ktClass.hasViewGroupSuperTypeHint())
        }
        val psiClass = javaFacade.findClass(this, searchScope)
            ?: return toViewDeclaration(alias, hasDeclaredLparams)
        if (!psiClass.isValidViewClass()) return null
        return toViewDeclaration(alias, psiClass.isAndroidViewGroup())
    }

    private fun String.isValidAnnotatedViewDeclaration(): Boolean {
        if (this == SystemSymbols.KOTLIN_ANY || this == SystemSymbols.JAVA_LANG_OBJECT) return false
        findProjectKtClass(this)?.let { ktClass -> return ktClass.isValidHikageViewClass() }
        val resolvedClass = javaFacade.findClass(this, searchScope) ?: return false
        return resolvedClass.isValidViewClass()
    }

    private fun String.toViewDeclaration(alias: String?, isViewGroup: Boolean): ViewDeclaration? {
        if (this == AndroidSymbols.VIEW_GROUP) return null
        val packageName = packageName() ?: return null
        val className = removePrefix("$packageName.")
        val resolvedAlias = alias?.takeIf(String::isNotBlank)
            ?: className.takeIf { name -> name.contains(".") }?.replace(".", "_")
        if (resolvedAlias != null && !resolvedAlias.isValidPerformerName()) return null
        return ViewDeclaration(packageName, className, resolvedAlias, isViewGroup)
    }

    private fun ViewDeclaration.toPerformerDeclaration(
        spec: PerformerSpec,
        source: Source,
        originFile: VirtualFile?
    ): PerformerDeclaration? {
        val explicitLparams = spec.lparams
            ?.takeUnless { lparams -> lparams == SystemSymbols.KOTLIN_ANY || lparams == SystemSymbols.JAVA_LANG_OBJECT }
            ?.let { lparams -> lparams to javaFacade.findClass(lparams, searchScope) }
        val resolvedLparams = explicitLparams?.second
        val effectiveLparams = when {
            !isViewGroup -> null
            explicitLparams == null -> AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS
            resolvedLparams != null && !resolvedLparams.isAndroidLayoutParams() -> return null
            else -> explicitLparams.first
        }
        return PerformerDeclaration(spec.copy(lparams = effectiveLparams), this, source, originFile)
    }

    private fun KtClassOrObject.findAnnotation(annotationFqName: String) = annotationEntries.firstOrNull { entry ->
        val file = containingKtFile
        val typeText = entry.typeReference?.text ?: return@firstOrNull false
        typeText == annotationFqName || file.resolveClassName(typeText) == annotationFqName
    }

    private fun KtClassOrObject.ownClassFqName(): String? {
        val packageName = containingKtFile.packageFqName.asString().takeUnless(String::isBlank) ?: return null
        val className = generateSequence(this) { element ->
            element.parentSequence().filterIsInstance<KtClassOrObject>().firstOrNull()
        }.mapNotNull(KtClassOrObject::getName).toList().asReversed().joinToString(".")
            .takeUnless(String::isBlank)
            ?: return null
        return "$packageName.$className"
    }

    private fun KtClassOrObject.hasViewGroupSuperTypeHint(): Boolean {
        val file = containingKtFile
        return superTypeListEntries.any { entry ->
            val typeText = entry.typeReference?.text?.classNameText() ?: return@any false
            val className = file.resolveClassName(typeText) ?: return@any false
            className == AndroidSymbols.VIEW_GROUP || className.endsWith("ViewGroup")
        }
    }

    private fun KtClassOrObject.isValidHikageViewClass(): Boolean {
        val file = containingKtFile
        if (!hasAndroidViewSuperTypeHint(file)) return false
        return constructorParameters().any { parameters ->
            parameters.size >= 2 &&
                parameters[0].typeReference?.isClassType(file, AndroidSymbols.CONTEXT) == true &&
                parameters[1].typeReference?.isClassType(file, AndroidSymbols.ATTRIBUTE_SET) == true &&
                parameters[1].typeReference?.text?.trim()?.endsWith("?") == true &&
                parameters.drop(2).all { parameter -> parameter.defaultValue != null }
        }
    }

    private fun KtClassOrObject.hasAndroidViewSuperTypeHint(file: KtFile): Boolean {
        if (ownClassFqName() == AndroidSymbols.VIEW_GROUP) return false
        return superTypeListEntries.any { entry ->
            val typeText = entry.typeReference?.text?.classNameText() ?: return@any false
            val className = file.resolveClassName(typeText) ?: return@any false
            className == AndroidSymbols.VIEW || className == AndroidSymbols.VIEW_GROUP || className.endsWith("View")
        }
    }

    private fun KtClassOrObject.constructorParameters() = sequenceOf(primaryConstructor)
        .filterNotNull()
        .map(KtPrimaryConstructor::getValueParameters) + declarations.asSequence()
        .filterIsInstance<KtSecondaryConstructor>()
        .map(KtSecondaryConstructor::getValueParameters)

    private fun KtTypeReference.isClassType(file: KtFile, fqName: String) =
        file.resolveClassName(text.classNameText()) == fqName

    private fun findProjectKtClass(classFqName: String): KtClassOrObject? {
        val className = classFqName.substringAfterLast(".").substringBefore("$")
        return collectKtFilesContaining(className)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .firstOrNull { ktClass -> ktClass.ownClassFqName() == classFqName }
    }

    private fun KtAnnotationEntry.classLiteralAttribute(name: String): String? {
        val argument = valueArguments.firstOrNull { valueArgument ->
            valueArgument.getArgumentName()?.asName?.identifier == name
        } ?: valueArguments.getOrNull(if (name == VIEW_FIELD) 0 else -1)
        val typeText = argument?.getArgumentExpression()?.text?.classNameText() ?: return null
        if (!typeText.contains(".")) {
            val packageName = containingKtFile.packageFqName.asString()
            val localClassFqName = if (packageName.isBlank()) typeText else "$packageName.$typeText"
            // Kotlin resolves local classes before stale generated imports with the same simple name.
            if (findProjectKtClass(localClassFqName) != null) return localClassFqName
        }
        return containingKtFile.resolveClassName(typeText)
    }

    private fun KtAnnotationEntry.stringAttribute(name: String) = valueArguments.firstOrNull { argument ->
        argument.getArgumentName()?.asName?.identifier == name
    }?.getArgumentExpression()?.text?.removeSurrounding("\"")?.takeUnless(String::isBlank)

    private fun KtAnnotationEntry.booleanAttribute(name: String, defaultValue: Boolean) = valueArguments.firstOrNull { argument ->
        argument.getArgumentName()?.asName?.identifier == name
    }?.getArgumentExpression()?.text?.toBooleanStrictOrNull() ?: defaultValue

    private fun String.classNameText() = removeSuffix("::class")
        .substringBefore("<")
        .substringBefore("(")
        .trim()
        .removeSuffix("?")

    private fun String.isAndroidViewGroupClassNameWithoutAnalysis(): Boolean {
        findProjectKtClass(this)?.let { ktClass -> return ktClass.hasViewGroupSuperTypeHint() }
        return javaFacade.findClass(this, searchScope)?.isAndroidViewGroup() == true
    }

    private fun String.packageName(): String? {
        val parts = split(".")
        val classStartIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
            .takeIf { index -> index > 0 }
            ?: return null
        return parts.take(classStartIndex).joinToString(".")
    }

    private fun String.isValidPerformerName() = SourceVersion.isIdentifier(this) &&
        !SourceVersion.isKeyword(this) &&
        this !in KOTLIN_KEYWORDS &&
        '$' !in this &&
        Name.isValidIdentifier(this)

    private fun shouldSkipExistingHikagableFunction(declaration: PerformerDeclaration) = hasRealGeneratedFunction(declaration)

    private fun hasRealGeneratedFunction(declaration: PerformerDeclaration): Boolean {
        // Resolve-extension collection cannot re-enter K2 analysis. Restrict this check to local
        // KSP Kotlin PSI, which is the concrete output that must replace the in-memory declaration.
        val originFile = declaration.originFile ?: return false
        val projectModel = ProjectModels.find(project, originFile) ?: return false
        val generatedSourceDirectory = projectModel.buildDirectory
            .resolve(KSP_GENERATED_SOURCE_PATH)
            .toPath()
            .normalize()
        return FilenameIndex.getVirtualFilesByName("${declaration.functionName}.kt", searchScope)
            .asSequence()
            .filter { file ->
                file.isInLocalFileSystem && VfsUtilCore.virtualToIoFile(file).toPath().normalize()
                    .startsWith(generatedSourceDirectory)
            }
            .mapNotNull { file -> PsiManager.getInstance(project).findFile(file) }
            .filterIsInstance<KtFile>()
            .filter { file -> file.packageFqName.asString() == declaration.generatedPackageName }
            .flatMap { file -> file.declarations.asSequence().filterIsInstance<KtNamedFunction>() }
            .any { function -> function.name == declaration.functionName && function.hasHikagableAnnotation() }
    }

    private fun KtNamedFunction.hasHikagableAnnotation(): Boolean {
        val containingFile = containingKtFile
        return annotationEntries.any { entry ->
            val typeText = entry.typeReference?.text
            val referenceText = entry.shortName?.asString() ?: return@any false
            typeText == HikageSymbols.HIKAGABLE_ANNOTATION ||
                referenceText == HikageSymbols.HIKAGABLE_ANNOTATION_NAME && containingFile.hasHikagableImport()
        }
    }

    private fun KtFile.hasHikagableImport() = importDirectives.any { directive ->
        directive.importedFqName?.asString() == HikageSymbols.HIKAGABLE_ANNOTATION
    }

    private fun List<PerformerDeclaration>.withoutDuplicateGeneratedKeys() = groupBy(PerformerDeclaration::generatedKey)
        .filterValues { declarations -> declarations.size == 1 }
        .values
        .flatten()

    private fun VirtualFile.declarationSource(): Source? {
        val normalizedPath = path.replace('\\', '/')
        return when {
            normalizedPath.contains("/$PACKAGED_VIEW_DECLARATION_DIRECTORY/") -> Source.OPTIONAL_FILE
            normalizedPath.contains("/$VIEW_DECLARATION_DIRECTORY/") -> Source.STRICT_FILE
            else -> null
        }
    }

    private fun PsiClass.isValidViewClass(): Boolean {
        val viewClass = javaFacade.findClass(AndroidSymbols.VIEW, searchScope) ?: return false
        if (this == javaFacade.findClass(AndroidSymbols.VIEW_GROUP, searchScope)) return false
        if (this != viewClass && !isInheritor(viewClass, true)) return false

        val contextClass = javaFacade.findClass(AndroidSymbols.CONTEXT, searchScope) ?: return false
        val attributeSetClass = javaFacade.findClass(AndroidSymbols.ATTRIBUTE_SET, searchScope) ?: return false
        return constructors.any { constructor ->
            val parameters = constructor.parameterList.parameters
            parameters.size >= 2 &&
                parameters[0].isTypeOf(contextClass) &&
                parameters[1].isTypeOf(attributeSetClass) &&
                parameters[1].isNullable() &&
                parameters.drop(2).all { parameter -> parameter.hasHikageOptionalDefaultValue() }
        }
    }

    private fun PsiClass.isAndroidViewGroup(): Boolean {
        val baseClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiClass.isAndroidLayoutParams(): Boolean {
        val baseClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiParameter.hasHikageOptionalDefaultValue() = navigationElement.text?.contains("=") == true

    private fun KtClassOrObject.parentSequence() = generateSequence(parent) { element -> element.parent }
}