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
package com.highcapable.hikage.dsl.resolver

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.dsl.model.HikageViewAnnotation
import com.highcapable.hikage.dsl.model.HikageViewAnnotation.Argument
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.dsl.model.PerformerDeclaration.Source
import com.highcapable.hikage.dsl.model.PerformerSpec
import com.highcapable.hikage.dsl.model.ViewDeclaration
import com.highcapable.hikage.dsl.model.ViewDeclarationFileItem
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.project.model.gradle.GradleToolingModels
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.SystemSymbols
import com.highcapable.hikage.utils.extension.isNullable
import com.highcapable.hikage.utils.extension.isTypeOf
import com.highcapable.hikage.utils.extension.resolveClassName
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
class PerformerDeclarationCollector private constructor(private val project: Project) {

    companion object {

        private const val JSON_FILE_EXTENSION = "json"
        private const val PACKAGED_VIEW_DECLARATION_DIRECTORY = "META-INF/hikage/view-declaration/"
        private const val ENTRY_JAR_FILE_NAME = "classes.jar"

        /**
         * Returns an instance of [PerformerDeclarationCollector] for the given [project].
         * @param project the project to collect performer declarations for.
         * @return [PerformerDeclarationCollector]
         */
        fun from(project: Project) = PerformerDeclarationCollector(project)
    }

    private val javaFacade get() = JavaPsiFacade.getInstance(project)
    private val searchScope get() = GlobalSearchScope.allScope(project)
    private val annotationSearchScope get() = GlobalSearchScope.projectScope(project)
    private val annotationValues = AnnotationValueResolver.from(project)

    /**
     * Represents the result of a performer declaration collection.
     */
    data class Collection(
        val declarations: List<PerformerDeclaration>,
        val duplicateViewClasses: Set<String>
    )

    private data class AnnotationPerformerSpec(
        val alias: String?,
        val performer: PerformerSpec
    )

    private data class ViewDeclarationFileCollection(
        val declarations: List<PerformerDeclaration>,
        val viewClasses: List<String>
    )

    /** Returns the deterministic, conflict-free performer declarations available to K2. */
    fun collect() = collectResult().declarations

    /**
     * Collects declarations together with View identities that were defined more than once.
     *
     * Duplicate View identities come from enabled raw inputs, independently of whether a candidate can generate a
     * stub. A duplicate View blocks every associated stub, matching KSP's failed generation round. Remaining stub
     * candidates continue to follow KSP's annotation > strict declaration file > optional declaration file source
     * precedence.
     */
    fun collectResult(): Collection {
        val annotationPerformers = HikageViewAnnotation.entries.flatMap(::collectAnnotatedDeclarations)
        val strictFileDeclarations = collectViewDeclarationFiles(Source.STRICT_FILE)
        val optionalFileDeclarations = collectViewDeclarationFiles(Source.OPTIONAL_FILE)
        val strictFilePerformers = strictFileDeclarations.declarations
        val optionalFilePerformers = optionalFileDeclarations.declarations
        val duplicateViewClasses = (
            HikageViewAnnotation.entries.flatMap(::collectAnnotatedViewClasses) +
                strictFileDeclarations.viewClasses + optionalFileDeclarations.viewClasses
        ).groupingBy { viewClass -> viewClass }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        val annotationViewClasses = annotationPerformers.mapTo(mutableSetOf()) { declaration -> declaration.viewClass }
        val strictCandidates = strictFilePerformers.filter { declaration -> declaration.viewClass !in annotationViewClasses }
        val strictViewClasses = annotationViewClasses + strictCandidates.map(PerformerDeclaration::viewClass)
        val optionalCandidates = optionalFilePerformers.filter { declaration -> declaration.viewClass !in strictViewClasses }
        val declarations = (annotationPerformers + strictCandidates + optionalCandidates)
            .filterNot { declaration -> declaration.viewClass in duplicateViewClasses }
            .withoutDuplicateGeneratedKeys()
            .sortedWith(compareBy(PerformerDeclaration::generatedPackageName, PerformerDeclaration::functionName))

        return Collection(declarations, duplicateViewClasses)
    }

    /** Resolves the View identity represented by a supported Hikage annotation. */
    fun annotationViewClass(declaration: KtClassOrObject, annotation: KtAnnotationEntry) = HikageViewAnnotation.entries
        .firstOrNull { definition -> DeclarationMatcher.isHikageAnnotation(annotation, definition.fqName) }
        ?.let { definition ->
            when (definition) {
                HikageViewAnnotation.View -> declaration.ownClassFqName()
                HikageViewAnnotation.Declaration -> annotation.classLiteralAttribute(requireNotNull(definition.view))
            }
        }

    private fun collectAnnotatedDeclarations(definition: HikageViewAnnotation): List<PerformerDeclaration> {
        val annotationName = definition.fqName.substringAfterLast(".")
        return collectKtFilesContaining(annotationName)
            .asSequence()
            .sortedBy { file -> file.virtualFile?.url.orEmpty() }
            .filter { file -> file.virtualFile?.let(::isHikageCompilerEnabled) == true }
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .flatMap { declaration ->
                declaration.annotationEntries.asSequence().map { annotation ->
                    declaration.toAnnotatedPerformerDeclaration(annotation, definition)
                }
            }
            .filterNotNull()
            .toList()
    }

    private fun collectAnnotatedViewClasses(definition: HikageViewAnnotation): List<String> {
        val annotationName = definition.fqName.substringAfterLast(".")
        return collectKtFilesContaining(annotationName)
            .asSequence()
            .filter { file -> file.virtualFile?.let(::isHikageCompilerEnabled) == true }
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .flatMap { declaration ->
                declaration.annotationEntries.asSequence().map { annotation ->
                    annotation.takeIf { entry -> DeclarationMatcher.isHikageAnnotation(entry, definition.fqName) }
                        ?.let { entry -> annotationViewClass(declaration, entry) }
                }
            }
            .filterNotNull()
            .toList()
    }

    private fun collectKtFilesContaining(word: String) = linkedSetOf<KtFile>().apply {
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, annotationSearchScope, { file ->
            if (file is KtFile) this += file
            true
        }, true)
    }.toList()

    private fun collectViewDeclarationFiles(source: Source): ViewDeclarationFileCollection {
        if (source == Source.ANNOTATION) return ViewDeclarationFileCollection(emptyList(), emptyList())

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
        val items = modelOutputFiles.flatMap { (model, files) ->
            if (files.isNotEmpty()) files.asSequence().flatMap { file ->
                file.toViewDeclarationFileItems().asSequence()
            } else model.toInputViewDeclarationItems(source).asSequence()
        }.toList()

        return ViewDeclarationFileCollection(
            declarations = items.mapNotNull { item -> item.toPerformerDeclaration(source) },
            viewClasses = items.mapNotNull { item -> item.viewClass.trim().takeIf(String::isNotEmpty) }
        )
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
            .flatMap { file -> file.toViewDeclarationFileItems().asSequence() }
            .toList()
        Source.OPTIONAL_FILE -> optionalViewDeclarationInputArtifacts.asSequence()
            .map(::File)
            .filter(File::isFile)
            .sortedBy { artifact -> artifact.absolutePath }
            .flatMap { artifact -> artifact.toViewDeclarationFileItems().asSequence() }
            .toList()
        Source.ANNOTATION -> emptyList()
    }

    private fun isHikageCompilerEnabled(file: VirtualFile) = ProjectFileIndex.getInstance(project)
        .getModuleForFile(file)
        ?.let { module -> GradleToolingModels.find(module, HikageGradleToolingModel) }
        ?.isCompilerEnabled == true

    private fun VirtualFile.toViewDeclarationFileItems() = VfsUtilCore.loadText(this).toViewDeclarationFileItems()
    private fun File.toViewDeclarationFileItems() = inputStream().collectArchivedViewDeclarationFileItems()

    private fun InputStream.collectArchivedViewDeclarationFileItems(): List<ViewDeclarationFileItem> =
        ZipInputStream(this).use { archive ->
            buildList {
                generateSequence { archive.nextEntry }.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    when {
                        entry.name.startsWith(PACKAGED_VIEW_DECLARATION_DIRECTORY) && entry.name.endsWith(".$JSON_FILE_EXTENSION") ->
                            addAll(archive.readBytes().decodeToString()
                                .toViewDeclarationFileItems())
                        entry.name == ENTRY_JAR_FILE_NAME ->
                            addAll(ByteArrayInputStream(archive.readBytes())
                                .collectArchivedViewDeclarationFileItems())
                    }
                }
            }
        }

    private fun String.toViewDeclarationFileItems() = runCatching {
        Json.decodeFromString<List<ViewDeclarationFileItem>>(this)
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
        definition: HikageViewAnnotation
    ): PerformerDeclaration? {
        if (!DeclarationMatcher.isHikageAnnotation(annotation, definition.fqName)) return null
        if (definition === HikageViewAnnotation.Declaration &&
            (this !is KtObjectDeclaration || isCompanion())
        ) return null

        val viewClass = annotationViewClass(this, annotation) ?: return null
        val annotationSpec = annotation.performerSpec(definition) ?: return null
        val isViewGroup = when (definition) {
            HikageViewAnnotation.View -> {
                if (!isValidHikageViewClass()) return null
                hasViewGroupSuperTypeHint()
            }
            HikageViewAnnotation.Declaration -> {
                if (!viewClass.isValidAnnotatedViewDeclaration()) return null
                viewClass.isAndroidViewGroupClassNameWithoutAnalysis()
            }
        }
        val declaration = ViewDeclaration.from(viewClass, annotationSpec.alias, isViewGroup) ?: return null

        return declaration.toPerformerDeclaration(annotationSpec.performer, Source.ANNOTATION)
    }

    private fun String.toFileViewDeclaration(alias: String?, hasDeclaredLparams: Boolean): ViewDeclaration? {
        if (this == SystemSymbols.KOTLIN_ANY || this == SystemSymbols.JAVA_LANG_OBJECT) return null
        findProjectKtClass(this)?.let { ktClass ->
            if (!ktClass.isValidHikageViewClass()) return null
            return ViewDeclaration.from(this, alias, ktClass.hasViewGroupSuperTypeHint())
        }
        val psiClass = javaFacade.findClass(this, searchScope)
            ?: return ViewDeclaration.from(this, alias, hasDeclaredLparams)
        if (!psiClass.isValidViewClass()) return null

        return ViewDeclaration.from(this, alias, psiClass.isAndroidViewGroup())
    }

    private fun String.isValidAnnotatedViewDeclaration(): Boolean {
        if (this == SystemSymbols.KOTLIN_ANY || this == SystemSymbols.JAVA_LANG_OBJECT) return false

        findProjectKtClass(this)?.let { ktClass -> return ktClass.isValidHikageViewClass() }

        val resolvedClass = javaFacade.findClass(this, searchScope) ?: return false
        return resolvedClass.isValidViewClass()
    }

    private fun ViewDeclaration.toPerformerDeclaration(
        spec: PerformerSpec,
        source: Source
    ): PerformerDeclaration? {
        val explicitLparams = spec.lparams
            ?.takeUnless { lparams -> lparams == SystemSymbols.KOTLIN_ANY || lparams == SystemSymbols.JAVA_LANG_OBJECT }
        val effectiveLparams = when {
            !isViewGroup -> null
            explicitLparams == null -> AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS
            !explicitLparams.isAndroidLayoutParamsClassNameWithoutAnalysis() -> return null
            else -> explicitLparams
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

    private fun KtAnnotationEntry.performerSpec(definition: HikageViewAnnotation): AnnotationPerformerSpec? {
        val lparamsArgument = definition.lparams.value(this)
        val lparams = annotationValues.classLiteral(this, definition.lparams)
        if (lparamsArgument != null && lparams == null) return null

        val aliasArgument = definition.alias.value(this)
        val alias = annotationValues.string(this, definition.alias)
        if (aliasArgument != null && alias == null) return null

        val (attrs, init, performer) = listOf(definition.attrs, definition.init, definition.performer)
            .map { argument -> annotationValues.booleanOrDefault(this, argument, defaultValue = true) ?: return null }

        return AnnotationPerformerSpec(
            alias = alias?.takeUnless(String::isBlank),
            performer = PerformerSpec(
                lparams = lparams,
                attrs = attrs,
                init = init,
                performer = performer
            )
        )
    }

    private fun KtAnnotationEntry.classLiteralAttribute(argument: Argument) = annotationValues.classLiteral(this, argument)

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

    private fun String.isAndroidLayoutParamsClassNameWithoutAnalysis(visited: MutableSet<String> = mutableSetOf()): Boolean {
        if (this == AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS) return true
        if (!visited.add(this)) return false

        findProjectKtClass(this)?.let { ktClass ->
            return ktClass.superTypeListEntries.any { entry ->
                val typeText = entry.typeReference?.text?.classNameText() ?: return@any false
                val className = ktClass.containingKtFile.resolveClassName(typeText) ?: return@any false
                className.isAndroidLayoutParamsClassNameWithoutAnalysis(visited)
            }
        }

        return javaFacade.findClass(this, searchScope)
            ?.hasSuperClassNameWithoutAnalysis(AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS)
            ?: false
    }

    private fun List<PerformerDeclaration>.withoutDuplicateGeneratedKeys() = groupBy(PerformerDeclaration::generatedKey)
        .filterValues { declarations -> declarations.size == 1 }
        .values
        .flatten()

    private fun PsiClass.isValidViewClass(): Boolean {
        if (!hasSuperClassNameWithoutAnalysis(AndroidSymbols.VIEW_CLASS) ||
            hasClassNameWithoutAnalysis(AndroidSymbols.VIEW_GROUP_CLASS)
        ) return false

        return runCatching {
            val contextClass = javaFacade.findClass(AndroidSymbols.CONTEXT_CLASS, searchScope) ?: return false
            val attributeSetClass = javaFacade.findClass(AndroidSymbols.ATTRIBUTE_SET_CLASS, searchScope) ?: return false
            constructors.any { constructor ->
                val parameters = constructor.parameterList.parameters
                parameters.size >= 2 &&
                    parameters[0].isTypeOf(contextClass) &&
                    parameters[1].isTypeOf(attributeSetClass) &&
                    parameters[1].isNullable() &&
                    parameters.drop(2).all { parameter -> parameter.hasHikageOptionalDefaultValue() }
            }
        }.getOrDefault(false)
    }

    private fun PsiClass.isAndroidViewGroup() = hasSuperClassNameWithoutAnalysis(AndroidSymbols.VIEW_GROUP_CLASS)

    private fun PsiClass.hasClassNameWithoutAnalysis(className: String) = runCatching {
        qualifiedName == className
    }.getOrDefault(false)

    // KaResolveExtensionProvider forbids K2 analysis. Kotlin light classes may trigger it while
    // reading a supertype, so an inaccessible hierarchy must fail closed for stub generation.
    private fun PsiClass.hasSuperClassNameWithoutAnalysis(className: String) = runCatching {
        generateSequence(this) { psiClass -> psiClass.superClass }
            .mapNotNull(PsiClass::getQualifiedName)
            .any { superClassName -> superClassName == className }
    }.getOrDefault(false)

    private fun PsiParameter.hasHikageOptionalDefaultValue() = navigationElement.text?.contains("=") == true

    private fun KtClassOrObject.parentSequence() = generateSequence(parent) { element -> element.parent }
}