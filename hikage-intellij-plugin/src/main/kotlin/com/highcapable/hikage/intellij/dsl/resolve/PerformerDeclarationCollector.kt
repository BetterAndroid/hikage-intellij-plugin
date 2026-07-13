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
package com.highcapable.hikage.intellij.dsl.resolve

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.dsl.model.ViewDeclarationSpec
import com.highcapable.hikage.intellij.model.Symbols
import com.highcapable.hikage.intellij.utils.extension.isNullable
import com.highcapable.hikage.intellij.utils.extension.isTypeOf
import com.highcapable.hikage.intellij.utils.extension.resolveClassName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class PerformerDeclarationCollector(private val project: Project) {

    private companion object {

        const val VIEW_CLASS_FIELD = "viewClass"
        const val LPARAMS_FIELD = "lparams"
        const val ALIAS_FIELD = "alias"
        const val ATTRS_FIELD = "attrs"
        const val INIT_FIELD = "init"
        const val PERFORMER_FIELD = "performer"
        const val VIEW_FIELD = "view"

        const val JSON_FILE_EXTENSION = "json"
        const val JSON_FILE_SUFFIX = ".$JSON_FILE_EXTENSION"
    }

    private val javaFacade get() = JavaPsiFacade.getInstance(project)
    private val searchScope get() = GlobalSearchScope.allScope(project)
    private val annotationSearchScope get() = GlobalSearchScope.projectScope(project)

    fun collect(): List<PerformerDeclaration> {
        val hikageViewDeclarations = collectAnnotatedDeclarations(Symbols.HIKAGE_VIEW_ANNOTATION)
        val hikageViewDeclarationDeclarations = collectAnnotatedDeclarations(Symbols.HIKAGE_VIEW_DECLARATION_ANNOTATION)
        val fileDeclarations = collectViewDeclarationFiles()
        val declarations = hikageViewDeclarations + hikageViewDeclarationDeclarations + fileDeclarations

        return declarations
            .distinctBy(PerformerDeclaration::generatedKey)
            .filterNot(::hasRealGeneratedFunction)
            .sortedWith(compareBy(PerformerDeclaration::generatedPackageName, PerformerDeclaration::functionName))
    }

    private fun collectAnnotatedDeclarations(annotationFqName: String) =
        collectAnnotatedDeclarationsByScanningCandidateFiles(annotationFqName)

    private fun collectAnnotatedDeclarationsByScanningCandidateFiles(annotationFqName: String): List<PerformerDeclaration> {
        val annotationName = annotationFqName.substringAfterLast(".")
        return collectKtFilesContaining(annotationName)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .mapNotNull { ktClass -> ktClass.toViewDeclarationSpec(annotationFqName) }
            .mapNotNull { spec -> spec.toPerformerDeclaration() }
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

    private fun collectViewDeclarationFiles(): List<PerformerDeclaration> {
        val projectFiles = FilenameIndex.getAllFilesByExt(project, JSON_FILE_EXTENSION, searchScope)
            .asSequence()
            .filter { it.isProjectViewDeclarationFile() }
            .toList()
        val projectDeclarations = projectFiles
            .asSequence()
            .flatMap { parseViewDeclarationItems(VfsUtilCore.loadText(it)) }
            .mapNotNull { spec -> spec.toPerformerDeclaration() }
            .toList()

        val localPluginDeclarations = parsePluginViewDeclarationFiles(Symbols.VIEW_DECLARATION_DIRECTORY)
        val packagedPluginDeclarations = parsePluginViewDeclarationFiles(Symbols.PACKAGED_VIEW_DECLARATION_DIRECTORY)

        return projectDeclarations + localPluginDeclarations + packagedPluginDeclarations
    }

    private fun parsePluginViewDeclarationFiles(resourcePath: String): List<PerformerDeclaration> {
        val urls = sequenceOf(resourcePath, "$resourcePath/")
            .flatMap { javaClass.classLoader.getResources(it).asSequence() }
            .distinctBy { it.toExternalForm() }
            .toList()
        val declarations = urls
            .asSequence()
            .flatMap { url -> parsePluginViewDeclarationSpecs(resourcePath, url) }
            .mapNotNull { spec -> spec.toPerformerDeclaration() }
            .toList()

        return declarations
    }

    private fun parsePluginViewDeclarationSpecs(resourcePath: String, url: URL): Sequence<ViewDeclarationSpec> = when (url.protocol) {
        URLUtil.FILE_PROTOCOL -> runCatching {
            val rootPath = Path.of(url.toURI())
            when {
                Files.isDirectory(rootPath) -> Files.walk(rootPath).use { stream ->
                    stream.toList()
                        .asSequence()
                        .filter { path -> path.toString().endsWith(JSON_FILE_SUFFIX) }
                        .flatMap { path -> parseViewDeclarationItems(path.readText()) }
                }
                rootPath.toString().endsWith(JSON_FILE_SUFFIX) -> parseViewDeclarationItems(rootPath.readText())
                else -> emptySequence()
            }
        }.getOrDefault(emptySequence())
        URLUtil.JAR_PROTOCOL -> runCatching {
            val connection = url.openConnection() as JarURLConnection
            connection.jarFile.use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.startsWith(resourcePath) && entry.name.endsWith(JSON_FILE_SUFFIX) }
                    .flatMap { entry ->
                        jarFile.getInputStream(entry).bufferedReader().use { reader ->
                            parseViewDeclarationItems(reader.readText())
                        }
                    }
                    .toList()
                    .asSequence()
            }
        }.getOrDefault(emptySequence())
        else -> emptySequence()
    }

    private fun parseViewDeclarationItems(text: String) = runCatching {
        JsonParser.parseString(text)
            .asJsonArray
            .asSequence()
            .mapNotNull { element -> element.asJsonObject.toViewDeclarationSpec() }
    }.getOrDefault(emptySequence())

    private fun JsonObject.toViewDeclarationSpec(): ViewDeclarationSpec? {
        val viewClass = string(VIEW_CLASS_FIELD) ?: return null
        return ViewDeclarationSpec(
            viewClass = viewClass,
            alias = string(ALIAS_FIELD),
            lparamsClass = string(LPARAMS_FIELD),
            hasAttrs = boolean(ATTRS_FIELD, true),
            hasInit = boolean(INIT_FIELD, true),
            hasPerformer = boolean(PERFORMER_FIELD, true),
            source = viewClass,
            isViewGroupHint = viewClass.isAndroidViewGroupClassName()
        )
    }

    private fun ViewDeclarationSpec.toPerformerDeclaration(): PerformerDeclaration? {
        if (viewClass == Symbols.ANDROID_VIEW_GROUP) return null
        if (!isValidViewDeclarationSpec()) return null
        val packageName = viewClass.packageName() ?: return null
        val className = viewClass.classNameInPackage(packageName)
        val explicitLparamsClass = lparamsClass
            ?.takeUnless(String::isBlank)
            ?.takeUnless { name -> name == Symbols.JAVA_LANG_OBJECT }
            ?.takeUnless { name -> name == Symbols.KOTLIN_ANY }
        val isViewGroup = isViewGroupHint || explicitLparamsClass != null
        val resolvedLparamsClass = when {
            !isViewGroup -> null
            explicitLparamsClass == null -> Symbols.ANDROID_VIEW_GROUP_LAYOUT_PARAMS
            else -> {
                val resolvedClass = runCatching { javaFacade.findClass(explicitLparamsClass, searchScope) }.getOrNull()
                if (resolvedClass != null && !runCatching { resolvedClass.isAndroidLayoutParams() }.getOrDefault(false)) return null
                explicitLparamsClass
            }
        }

        return PerformerDeclaration(
            viewClass = viewClass,
            functionName = alias?.takeUnless(String::isBlank) ?: className.replace(".", "_"),
            generatedPackageName = "${Symbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.$packageName",
            lparamsClass = resolvedLparamsClass,
            hasAttrs = hasAttrs,
            hasInit = hasInit,
            hasPerformer = hasPerformer && resolvedLparamsClass != null,
            source = source
        )
    }

    private fun KtClassOrObject.toViewDeclarationSpec(annotationFqName: String): ViewDeclarationSpec? {
        val annotation = findAnnotation(annotationFqName) ?: return null
        val viewClass = when (annotationFqName) {
            Symbols.HIKAGE_VIEW_ANNOTATION -> ownClassFqName()
            else -> annotation.classLiteralAttribute(VIEW_FIELD)
        } ?: return null
        if (annotationFqName == Symbols.HIKAGE_VIEW_ANNOTATION && !isValidHikageViewClass()) return null
        if (annotationFqName == Symbols.HIKAGE_VIEW_DECLARATION_ANNOTATION &&
            !viewClass.isValidAnnotatedViewDeclaration()
        ) return null

        return ViewDeclarationSpec(
            viewClass = viewClass,
            alias = annotation.stringAttribute(ALIAS_FIELD),
            lparamsClass = annotation.classLiteralAttribute(LPARAMS_FIELD),
            hasAttrs = annotation.booleanAttribute(ATTRS_FIELD, true),
            hasInit = annotation.booleanAttribute(INIT_FIELD, true),
            hasPerformer = annotation.booleanAttribute(PERFORMER_FIELD, true),
            source = viewClass,
            isViewGroupHint = when (annotationFqName) {
                Symbols.HIKAGE_VIEW_ANNOTATION -> hasViewGroupSuperTypeHint()
                else -> viewClass.isAndroidViewGroupClassNameWithoutAnalysis()
            }
        )
    }

    private fun KtClassOrObject.findAnnotation(annotationFqName: String) = annotationEntries.firstOrNull { entry ->
        val file = containingKtFile
        val typeText = entry.typeReference?.text ?: return@firstOrNull false
        typeText == annotationFqName || file.resolveClassName(typeText) == annotationFqName
    }

    private fun KtClassOrObject.ownClassFqName(): String? {
        val packageName = containingKtFile.packageFqName.asString()
            .takeUnless(String::isBlank)
            ?: return null
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
            className == Symbols.ANDROID_VIEW_GROUP || className.endsWith("ViewGroup")
        }
    }

    private fun KtClassOrObject.isValidHikageViewClass(): Boolean {
        val file = containingKtFile
        if (!hasAndroidViewSuperTypeHint(file)) return false
        return constructorParameters().any { parameters ->
            parameters.size >= 2 &&
                parameters[0].typeReference?.isClassType(file, Symbols.ANDROID_CONTEXT) == true &&
                parameters[1].typeReference?.isClassType(file, Symbols.ANDROID_ATTRIBUTE_SET) == true &&
                parameters[1].typeReference?.text?.trim()?.endsWith("?") == true &&
                parameters.drop(2).all { parameter -> parameter.defaultValue != null }
        }
    }

    private fun KtClassOrObject.hasAndroidViewSuperTypeHint(file: KtFile): Boolean {
        if (ownClassFqName() == Symbols.ANDROID_VIEW_GROUP) return false
        return superTypeListEntries.any { entry ->
            val typeText = entry.typeReference?.text?.classNameText() ?: return@any false
            val className = file.resolveClassName(typeText) ?: return@any false
            className == Symbols.ANDROID_VIEW || className == Symbols.ANDROID_VIEW_GROUP || className.endsWith("View")
        }
    }

    private fun KtClassOrObject.constructorParameters() = sequenceOf(primaryConstructor)
        .filterNotNull()
        .map(KtPrimaryConstructor::getValueParameters) + declarations.asSequence()
        .filterIsInstance<KtSecondaryConstructor>()
        .map(KtSecondaryConstructor::getValueParameters)

    private fun KtTypeReference.isClassType(file: KtFile, fqName: String): Boolean {
        val typeText = text.classNameText()
        return file.resolveClassName(typeText) == fqName
    }

    private fun ViewDeclarationSpec.isValidViewDeclarationSpec(): Boolean {
        if (viewClass == Symbols.KOTLIN_ANY || viewClass == Symbols.JAVA_LANG_OBJECT) return false
        if (source == viewClass && viewClass.startsWith(Symbols.HIKAGE_WIDGET_PACKAGE_PREFIX)) return true
        findProjectKtClass(viewClass)?.let { ktClass -> return ktClass.isValidHikageViewClass() }
        val resolvedClass = runCatching { javaFacade.findClass(viewClass, searchScope) }.getOrNull() ?: return true
        return runCatching { resolvedClass.isValidViewClass() }.getOrDefault(false)
    }

    private fun String.isValidAnnotatedViewDeclaration(): Boolean {
        if (this == Symbols.KOTLIN_ANY || this == Symbols.JAVA_LANG_OBJECT) return false
        findProjectKtClass(this)?.let { ktClass -> return ktClass.isValidHikageViewClass() }
        val resolvedClass = runCatching { javaFacade.findClass(this, searchScope) }.getOrNull() ?: return false

        return runCatching { resolvedClass.isValidViewClass() }.getOrDefault(false)
    }

    private fun findProjectKtClass(classFqName: String): KtClassOrObject? {
        val className = classFqName.substringAfterLast(".").substringBefore("$")
        return collectKtFilesContaining(className)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .firstOrNull { ktClass -> ktClass.ownClassFqName() == classFqName }
    }

    private fun KtAnnotationEntry.classLiteralAttribute(name: String): String? {
        val argument = valueArguments.firstOrNull { argument ->
            argument.getArgumentName()?.asName?.identifier == name
        } ?: valueArguments.getOrNull(if (name == VIEW_FIELD) 0 else -1)
        val typeText = argument?.getArgumentExpression()?.text?.classNameText() ?: return null
        if (!typeText.contains(".")) {
            val packageName = containingKtFile.packageFqName.asString()
            val localClassFqName = if (packageName.isBlank()) typeText else "$packageName.$typeText"

            // A stale generated performer import may have the same name as a project class.
            // Kotlin resolves a class literal to the local class, so mirror that precedence
            // before considering imports to avoid permanently suppressing its replacement stub.
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

    private fun String.isAndroidViewGroupClassName(): Boolean {
        if (this == Symbols.ANDROID_VIEW_GROUP) return true
        return runCatching {
            javaFacade.findClass(this, searchScope)?.isAndroidViewGroup() == true
        }.getOrDefault(false)
    }

    private fun String.isAndroidViewGroupClassNameWithoutAnalysis(): Boolean {
        findProjectKtClass(this)?.let { ktClass -> return ktClass.hasViewGroupSuperTypeHint() }
        return isAndroidViewGroupClassName()
    }

    private fun String.packageName(): String? {
        val parts = split(".")
        val classStartIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
            .takeIf { index -> index > 0 }
            ?: return null
        return parts.take(classStartIndex).joinToString(".")
    }

    private fun String.classNameInPackage(packageName: String) = removePrefix("$packageName.")

    private fun hasRealGeneratedFunction(declaration: PerformerDeclaration): Boolean {
        // KaResolveExtensionProvider runs while Kotlin explicitly forbids nested analysis.
        // Looking up the generated facade class and reading its light-class methods re-enters
        // K2 analysis. Keep this check at filename + Kotlin PSI declaration level, and only
        // treat local KSP output as the real generated source. User-authored @Hikagable
        // functions with the same name must not suppress the dynamic IDE stub.
        return FilenameIndex.getVirtualFilesByName("${declaration.functionName}.kt", searchScope)
            .asSequence()
            .filter { file -> file.isInLocalFileSystem && file.path.contains(Symbols.KSP_GENERATED_SOURCE_PATH_MARKER) }
            .mapNotNull { file -> PsiManager.getInstance(project).findFile(file) }
            .filterIsInstance<KtFile>()
            .filter { file -> file.packageFqName.asString() == declaration.generatedPackageName }
            .flatMap { file -> file.declarations.asSequence().filterIsInstance<KtNamedFunction>() }
            .any { function ->
                function.name == declaration.functionName && function.hasHikagableAnnotation()
            }
    }

    private fun KtNamedFunction.hasHikagableAnnotation(): Boolean {
        val containingFile = containingKtFile
        return annotationEntries.any { entry ->
            val typeText = entry.typeReference?.text
            val referenceText = entry.shortName?.asString() ?: return@any false
            typeText == Symbols.HIKAGABLE_ANNOTATION ||
                referenceText == Symbols.HIKAGABLE_ANNOTATION_NAME && containingFile.hasHikagableImport()
        }
    }

    private fun KtFile.hasHikagableImport() = importDirectives.any { directive ->
        directive.importedFqName?.asString() == Symbols.HIKAGABLE_ANNOTATION
    }

    private fun JsonObject.string(name: String) =
        get(name)?.takeUnless { value -> value.isJsonNull }?.asString

    private fun JsonObject.boolean(name: String, defaultValue: Boolean) =
        get(name)?.takeUnless { value -> value.isJsonNull }?.asBoolean ?: defaultValue

    private fun VirtualFile.isProjectViewDeclarationFile(): Boolean {
        val normalizedPath = path.replace('\\', '/')
        return normalizedPath.contains("/${Symbols.VIEW_DECLARATION_DIRECTORY}/") ||
            normalizedPath.contains("/${Symbols.PACKAGED_VIEW_DECLARATION_DIRECTORY}/")
    }

    private fun PsiClass.isValidViewClass(): Boolean {
        val viewClass = javaFacade.findClass(Symbols.ANDROID_VIEW, searchScope) ?: return false
        if (this == javaFacade.findClass(Symbols.ANDROID_VIEW_GROUP, searchScope)) return false
        if (this != viewClass && !isInheritor(viewClass, true)) return false

        val contextClass = javaFacade.findClass(Symbols.ANDROID_CONTEXT, searchScope) ?: return false
        val attributeSetClass = javaFacade.findClass(Symbols.ANDROID_ATTRIBUTE_SET, searchScope) ?: return false
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
        val baseClass = javaFacade.findClass(Symbols.ANDROID_VIEW_GROUP, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiClass.isAndroidLayoutParams(): Boolean {
        val baseClass = javaFacade.findClass(Symbols.ANDROID_VIEW_GROUP_LAYOUT_PARAMS, searchScope) ?: return false
        return this == baseClass || isInheritor(baseClass, true)
    }

    private fun PsiParameter.hasHikageOptionalDefaultValue(): Boolean {
        val navigationElementText = navigationElement.text ?: return false
        return navigationElementText.contains("=")
    }

    private fun KtClassOrObject.parentSequence() = generateSequence(parent) { element -> element.parent }
}