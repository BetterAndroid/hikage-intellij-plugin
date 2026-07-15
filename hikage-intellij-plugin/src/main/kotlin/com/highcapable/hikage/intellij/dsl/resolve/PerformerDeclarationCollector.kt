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

import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.intellij.dsl.detector.DeclarationMatcher
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.intellij.dsl.model.PerformerSpec
import com.highcapable.hikage.intellij.dsl.model.ViewDeclaration
import com.highcapable.hikage.intellij.dsl.model.ViewDeclarationFileItem
import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.model.SystemSymbols
import com.highcapable.hikage.intellij.project.model.gradle.GradleToolingModels
import com.highcapable.hikage.intellij.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.intellij.utils.ClassDetector
import com.highcapable.hikage.intellij.utils.extension.isNullable
import com.highcapable.hikage.intellij.utils.extension.isTypeOf
import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

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

        const val JSON_FILE_EXTENSION = "json"
        const val PACKAGED_VIEW_DECLARATION_DIRECTORY = "META-INF/hikage/view-declaration/"
        const val ENTRY_JAR_FILE_NAME = "classes.jar"
    }

    private val javaFacade get() = JavaPsiFacade.getInstance(project)
    private val searchScope get() = GlobalSearchScope.allScope(project)
    private val annotationSearchScope get() = GlobalSearchScope.projectScope(project)

    /**
     * Represents the result of a performer declaration collection.
     */
    data class Collection(
        val declarations: List<PerformerDeclaration>,
        val duplicateViewClasses: Set<String>
    )

    /** Returns the deterministic, conflict-free performer declarations available to K2. */
    fun collect() = collectResult().declarations

    /**
     * Collects declarations together with View identities that were defined more than once.
     *
     * Duplicate View identities are retained for inspection reporting. Stub candidates continue to follow KSP's
     * annotation > strict declaration file > optional declaration file source precedence.
     */
    fun collectResult(): Collection {
        val annotationPerformers = collectAnnotatedDeclarations(HikageSymbols.HIKAGE_VIEW_ANNOTATION) +
            collectAnnotatedDeclarations(HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION)
        val strictFilePerformers = collectViewDeclarationFiles(Source.STRICT_FILE)
        val optionalFilePerformers = collectViewDeclarationFiles(Source.OPTIONAL_FILE)
        val allCandidates = annotationPerformers + strictFilePerformers + optionalFilePerformers
        val duplicateViewClasses = allCandidates.groupingBy(PerformerDeclaration::viewClass)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        val annotationViewClasses = annotationPerformers.mapTo(mutableSetOf()) { declaration -> declaration.viewClass }
        val strictCandidates = strictFilePerformers.filter { declaration -> declaration.viewClass !in annotationViewClasses }
        val strictViewClasses = annotationViewClasses + strictCandidates.map(PerformerDeclaration::viewClass)
        val optionalCandidates = optionalFilePerformers.filter { declaration -> declaration.viewClass !in strictViewClasses }
        val declarations = (annotationPerformers + strictCandidates + optionalCandidates)
            .withoutDuplicateGeneratedKeys()
            .sortedWith(compareBy(PerformerDeclaration::generatedPackageName, PerformerDeclaration::functionName))

        return Collection(declarations, duplicateViewClasses)
    }

    /** Resolves the View identity represented by a supported Hikage annotation. */
    fun annotationViewClass(declaration: KtClassOrObject, annotation: KtAnnotationEntry) = when {
        DeclarationMatcher.isHikageAnnotation(annotation, HikageSymbols.HIKAGE_VIEW_ANNOTATION) -> declaration.ownClassFqName()
        DeclarationMatcher.isHikageAnnotation(annotation, HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION) ->
            annotation.classLiteralAttribute(VIEW_FIELD)
        else -> null
    }

    private fun collectAnnotatedDeclarations(annotationFqName: String): List<PerformerDeclaration> {
        val annotationName = annotationFqName.substringAfterLast(".")
        return collectKtFilesContaining(annotationName)
            .asSequence()
            .sortedBy { file -> file.virtualFile?.url.orEmpty() }
            .filter { file -> file.virtualFile?.let(::isHikageCompilerEnabled) == true }
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .flatMap { declaration ->
                declaration.annotationEntries.asSequence().map { annotation ->
                    declaration.toAnnotatedPerformerDeclaration(annotation, annotationFqName)
                }
            }
            .filterNotNull()
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
        if (source == Source.ANNOTATION) return emptyList()

        val models = GradleToolingModels.all(project, HikageGradleToolingModel)
        val enabledModels = models.filter(HikageGradleModel::isCompilerEnabled)
        val modelOutputFiles = enabledModels.map { model ->
            model to model.declarationPaths(source)
                .asSequence()
                .mapNotNull { path -> LocalFileSystem.getInstance().findFileByPath(path) }
                .flatMap { file -> if (file.isDirectory) file.collectJsonFiles().asSequence() else sequenceOf(file) }
                .distinctBy { file -> file.url }
                .sortedBy { file -> file.url }
                .toList()
        }
        val declarations = modelOutputFiles.flatMap { (model, files) ->
            if (files.isNotEmpty()) files.asSequence().flatMap { file ->
                file.toViewDeclarationFileItems(source).asSequence()
            } else model.toInputViewDeclarationItems(source).asSequence()
        }.toList()

        return declarations
    }

    private fun VirtualFile.collectJsonFiles() = buildList {
        VfsUtilCore.iterateChildrenRecursively(
            this@collectJsonFiles,
            { file -> file.isDirectory || file.extension == JSON_FILE_EXTENSION }
        ) { file ->
            if (!file.isDirectory) add(file)
            true
        }
    }

    private fun HikageGradleModel.declarationPaths(source: Source) = when (source) {
        Source.STRICT_FILE -> viewDeclarationFiles
        Source.OPTIONAL_FILE -> optionalViewDeclarationFiles
        Source.ANNOTATION -> emptyList()
    }

    private fun HikageGradleModel.toInputViewDeclarationItems(source: Source) = when (source) {
        Source.STRICT_FILE -> strictViewDeclarationInputFiles.asSequence()
            .sorted()
            .mapNotNull { path -> LocalFileSystem.getInstance().findFileByPath(path) }
            .flatMap { file -> file.toViewDeclarationFileItems(source).asSequence() }
            .toList()
        Source.OPTIONAL_FILE -> optionalViewDeclarationInputArtifacts.asSequence()
            .map(::File)
            .filter(File::isFile)
            .sortedBy { artifact -> artifact.absolutePath }
            .flatMap { artifact -> artifact.toViewDeclarationFileItems(source).asSequence() }
            .toList()
        Source.ANNOTATION -> emptyList()
    }

    private fun isHikageCompilerEnabled(file: VirtualFile) = ProjectFileIndex.getInstance(project)
        .getModuleForFile(file)
        ?.let { module -> GradleToolingModels.find(module, HikageGradleToolingModel) }
        ?.isCompilerEnabled == true

    private fun VirtualFile.toViewDeclarationFileItems(source: Source) = VfsUtilCore.loadText(this).toViewDeclarationFileItems(source)
    private fun File.toViewDeclarationFileItems(source: Source) = inputStream().collectArchivedViewDeclarationFileItems(source)

    private fun InputStream.collectArchivedViewDeclarationFileItems(source: Source): List<PerformerDeclaration> =
        ZipInputStream(this).use { archive ->
            buildList {
                generateSequence { archive.nextEntry }.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    when {
                        entry.name.startsWith(PACKAGED_VIEW_DECLARATION_DIRECTORY) && entry.name.endsWith(".$JSON_FILE_EXTENSION") ->
                            addAll(archive.readBytes().decodeToString()
                                .toViewDeclarationFileItems(source))
                        entry.name == ENTRY_JAR_FILE_NAME ->
                            addAll(ByteArrayInputStream(archive.readBytes())
                                .collectArchivedViewDeclarationFileItems(source))
                    }
                }
            }
        }

    private fun String.toViewDeclarationFileItems(source: Source) = runCatching {
        Json.decodeFromString<List<ViewDeclarationFileItem>>(this).mapNotNull { item ->
            item.toPerformerDeclaration(source)
        }
    }.getOrNull() ?: emptyList()

    private fun ViewDeclarationFileItem.toPerformerDeclaration(source: Source): PerformerDeclaration? {
        val viewClass = viewClass.trim().takeIf(String::isNotEmpty) ?: return null
        val lparams = lparams?.trim()?.takeIf(String::isNotEmpty)
        val declaration = viewClass.toFileViewDeclaration(alias, lparams != null) ?: return null
        val spec = PerformerSpec(
            lparams = lparams,
            attrs = attrs,
            init = init,
            performer = performer
        )

        return declaration.toPerformerDeclaration(spec, source)
    }

    private fun KtClassOrObject.toAnnotatedPerformerDeclaration(
        annotation: KtAnnotationEntry,
        annotationFqName: String
    ): PerformerDeclaration? {
        if (!DeclarationMatcher.isHikageAnnotation(annotation, annotationFqName)) return null
        if (annotationFqName == HikageSymbols.HIKAGE_VIEW_DECLARATION_ANNOTATION &&
            (this !is KtObjectDeclaration || isCompanion())
        ) return null

        val viewClass = annotationViewClass(this, annotation) ?: return null
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

        return declaration.toPerformerDeclaration(spec, Source.ANNOTATION)
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
        if (this == AndroidSymbols.VIEW_GROUP_CLASS) return null

        val packageName = packageName() ?: return null
        val className = removePrefix("$packageName.")
        val resolvedAlias = alias?.takeIf(String::isNotBlank)
            ?: className.takeIf { name -> name.contains(".") }?.replace(".", "_")
        if (resolvedAlias != null && !ClassDetector.verify(resolvedAlias)) return null

        return ViewDeclaration(packageName, className, resolvedAlias, isViewGroup)
    }

    private fun ViewDeclaration.toPerformerDeclaration(
        spec: PerformerSpec,
        source: Source
    ): PerformerDeclaration? {
        val explicitLparams = spec.lparams
            ?.takeUnless { lparams -> lparams == SystemSymbols.KOTLIN_ANY || lparams == SystemSymbols.JAVA_LANG_OBJECT }
            ?.let { lparams -> lparams to javaFacade.findClass(lparams, searchScope) }
        val resolvedLparams = explicitLparams?.second
        val effectiveLparams = when {
            !isViewGroup -> null
            explicitLparams == null -> AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
            resolvedLparams != null && !resolvedLparams.isAndroidLayoutParams() -> return null
            else -> explicitLparams.first
        }

        return PerformerDeclaration(spec.copy(lparams = effectiveLparams), this, source)
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

            className == AndroidSymbols.VIEW_GROUP_CLASS ||
                className.endsWith(AndroidSymbols.VIEW_GROUP_NAME) ||
                className.isAndroidViewGroupClassNameWithoutAnalysis()
        }
    }

    private fun KtClassOrObject.isValidHikageViewClass(): Boolean {
        val file = containingKtFile
        if (!hasAndroidViewSuperTypeHint(file)) return false
        return constructorParameters().any { parameters ->
            parameters.size >= 2 &&
                parameters[0].typeReference?.isClassType(file, AndroidSymbols.CONTEXT_CLASS) == true &&
                parameters[1].typeReference?.isClassType(file, AndroidSymbols.ATTRIBUTE_SET_CLASS) == true &&
                parameters[1].typeReference?.text?.trim()?.endsWith("?") == true &&
                parameters.drop(2).all { parameter -> parameter.defaultValue != null }
        }
    }

    private fun KtClassOrObject.hasAndroidViewSuperTypeHint(file: KtFile): Boolean {
        if (ownClassFqName() == AndroidSymbols.VIEW_GROUP_CLASS) return false
        return superTypeListEntries.any { entry ->
            val typeText = entry.typeReference?.text?.classNameText() ?: return@any false
            val className = file.resolveClassName(typeText) ?: return@any false

            className == AndroidSymbols.VIEW_CLASS ||
                className == AndroidSymbols.VIEW_GROUP_CLASS ||
                className.endsWith(AndroidSymbols.VIEW_NAME) ||
                className.isAndroidViewClassNameWithoutAnalysis()
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

    private fun String.isAndroidViewClassNameWithoutAnalysis(): Boolean {
        findProjectKtClass(this)?.let { ktClass ->
            return ktClass.hasAndroidViewSuperTypeHint(ktClass.containingKtFile)
        }
        return javaFacade.findClass(this, searchScope)?.isValidViewClass() == true
    }

    private fun String.packageName(): String? {
        val parts = split(".")
        val classStartIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
            .takeIf { index -> index > 0 }
            ?: return null
        return parts.take(classStartIndex).joinToString(".")
    }

    private fun List<PerformerDeclaration>.withoutDuplicateGeneratedKeys() = groupBy(PerformerDeclaration::generatedKey)
        .filterValues { declarations -> declarations.size == 1 }
        .values
        .flatten()

    private fun PsiClass.isValidViewClass(): Boolean {
        val viewClass = javaFacade.findClass(AndroidSymbols.VIEW_CLASS, searchScope) ?: return false
        if (this == javaFacade.findClass(AndroidSymbols.VIEW_GROUP_CLASS, searchScope)) return false
        if (this != viewClass && !isInheritor(viewClass, true)) return false

        val contextClass = javaFacade.findClass(AndroidSymbols.CONTEXT_CLASS, searchScope) ?: return false
        val attributeSetClass = javaFacade.findClass(AndroidSymbols.ATTRIBUTE_SET_CLASS, searchScope) ?: return false

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
        val baseClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP_CLASS, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiClass.isAndroidLayoutParams(): Boolean {
        val baseClass = javaFacade.findClass(AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiParameter.hasHikageOptionalDefaultValue() = navigationElement.text?.contains("=") == true

    private fun KtClassOrObject.parentSequence() = generateSequence(parent) { element -> element.parent }
}