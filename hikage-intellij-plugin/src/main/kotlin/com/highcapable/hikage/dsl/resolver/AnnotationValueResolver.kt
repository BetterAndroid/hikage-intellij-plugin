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
 */
@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.highcapable.hikage.dsl.resolver

import com.highcapable.hikage.dsl.model.HikageViewAnnotation.Argument
import com.highcapable.hikage.utils.extension.resolveClassName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Resolves annotation values through PSI without entering the Analysis API from a resolve extension.
 */
class AnnotationValueResolver private constructor(private val project: Project) {

    companion object {

        /**
         * Returns an instance of [AnnotationValueResolver] for the given [project].
         * @param project the project to resolve annotation values for.
         * @return [AnnotationValueResolver]
         */
        fun from(project: Project) = AnnotationValueResolver(project)
    }

    private val searchScope = GlobalSearchScope.projectScope(project)

    internal fun classLiteral(annotation: KtAnnotationEntry, argument: Argument): String? {
        val expression = argument.expression(annotation)
            as? KtClassLiteralExpression
            ?: return null
        val typeText = expression.receiverExpression?.text?.classNameText() ?: return null
        if (!typeText.contains(".")) {
            val packageName = annotation.containingKtFile.packageFqName.asString()
            val localClassFqName = if (packageName.isBlank()) typeText else "$packageName.$typeText"
            if (findProjectClass(localClassFqName) != null) return localClassFqName
        }

        return annotation.containingKtFile.resolveClassName(typeText)
    }

    internal fun string(annotation: KtAnnotationEntry, argument: Argument): String? =
        argument.expression(annotation)
            ?.stringConstantValue(mutableSetOf())

    internal fun booleanOrDefault(annotation: KtAnnotationEntry, argument: Argument, defaultValue: Boolean): Boolean? {
        val valueArgument = argument.value(annotation) ?: return defaultValue
        val expression = valueArgument.getArgumentExpression() ?: return null
        return expression.booleanConstantValue(mutableSetOf())
    }

    private fun KtExpression.stringConstantValue(visited: MutableSet<String>): String? = when (this) {
        is KtStringTemplateExpression -> entries.map { entry ->
            when (entry) {
                is KtLiteralStringTemplateEntry -> entry.text.let { text ->
                    if (this@stringConstantValue.text.startsWith("\"\"\"")) text else StringUtil.unescapeStringCharacters(text)
                }
                is KtStringTemplateEntryWithExpression -> entry.expression?.stringConstantValue(visited)
                else -> null
            }
        }.let { values -> if (values.any { value -> value == null }) null else values.joinToString(separator = "") }
        is KtBinaryExpression -> {
            if (operationToken != KtTokens.PLUS) return null
            val left = left?.stringConstantValue(visited) ?: return null
            val right = right?.stringConstantValue(visited) ?: return null

            left + right
        }
        else -> constantProperty()?.stringConstantValue(visited)
    }

    private fun KtExpression.booleanConstantValue(visited: MutableSet<String>): Boolean? = when (this) {
        is KtConstantExpression -> text.toBooleanStrictOrNull()
        is KtPrefixExpression -> {
            if (operationToken != KtTokens.EXCL) return null
            baseExpression?.booleanConstantValue(visited)?.not()
        }
        is KtBinaryExpression -> when (operationToken) {
            KtTokens.ANDAND -> {
                val left = left?.booleanConstantValue(visited) ?: return null
                val right = right?.booleanConstantValue(visited) ?: return null

                left && right
            }
            KtTokens.OROR -> {
                val left = left?.booleanConstantValue(visited) ?: return null
                val right = right?.booleanConstantValue(visited) ?: return null

                left || right
            }
            else -> null
        }
        else -> constantProperty()?.booleanConstantValue(visited)
    }

    private fun KtProperty.stringConstantValue(visited: MutableSet<String>): String? {
        val key = qualifiedName() ?: return null
        if (!visited.add(key)) return null

        return initializer?.stringConstantValue(visited).also { visited -= key }
    }

    private fun KtProperty.booleanConstantValue(visited: MutableSet<String>): Boolean? {
        val key = qualifiedName() ?: return null
        if (!visited.add(key)) return null

        return initializer?.booleanConstantValue(visited).also { visited -= key }
    }

    private fun KtExpression.constantProperty(): KtProperty? {
        val referenceName = when (this) {
            is KtNameReferenceExpression -> getReferencedName()
            is KtDotQualifiedExpression -> text
            else -> return null
        }
        val candidates = containingKtFile.constantPropertyCandidates(referenceName, this)
        if (candidates.isEmpty()) return null

        val propertyName = referenceName.substringAfterLast('.')
        return collectKtFilesContaining(propertyName)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtProperty>().asSequence() }
            .filter { property -> property.hasModifier(KtTokens.CONST_KEYWORD) }
            .singleOrNull { property -> property.qualifiedName() in candidates }
    }

    private fun KtFile.constantPropertyCandidates(referenceName: String, context: PsiElement) = buildSet {
        if (referenceName.contains('.') && referenceName.substringBefore('.').firstOrNull()?.isLowerCase() == true)
            add(referenceName)

        importDirectives.forEach { directive ->
            val importedFqName = directive.importedFqName?.asString() ?: return@forEach
            if (directive.isAllUnder) {
                add("$importedFqName.$referenceName")
                return@forEach
            }

            val importName = directive.aliasName ?: importedFqName.substringAfterLast('.')
            if (referenceName == importName || referenceName.startsWith("$importName."))
                add(importedFqName + referenceName.removePrefix(importName))
        }

        val packageName = packageFqName.asString()
        if (packageName.isNotBlank()) add("$packageName.$referenceName") else add(referenceName)

        val owners = generateSequence(context.parent) { element -> element.parent }
            .filterIsInstance<KtClassOrObject>()
            .mapNotNull(KtClassOrObject::getName)
            .toList()
            .asReversed()
        owners.indices.reversed().forEach { index ->
            val ownerName = owners.take(index + 1).joinToString(".")
            add(listOf(packageName, ownerName, referenceName).filter(String::isNotBlank).joinToString("."))
        }
    }

    private fun KtProperty.qualifiedName(): String? {
        val propertyName = name ?: return null
        val packageName = containingKtFile.packageFqName.asString()
        val owners = generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtClassOrObject>()
            .mapNotNull(KtClassOrObject::getName)
            .toList()
            .asReversed()

        return listOf(packageName, owners.joinToString("."), propertyName)
            .filter(String::isNotBlank)
            .joinToString(".")
    }

    private fun findProjectClass(classFqName: String): KtClassOrObject? {
        val className = classFqName.substringAfterLast('.').substringBefore('$')
        return collectKtFilesContaining(className)
            .asSequence()
            .flatMap { file -> file.collectDescendantsOfType<KtClassOrObject>().asSequence() }
            .firstOrNull { ktClass -> ktClass.qualifiedName() == classFqName }
    }

    private fun KtClassOrObject.qualifiedName(): String? {
        val packageName = containingKtFile.packageFqName.asString().takeUnless(String::isBlank) ?: return null
        val className = generateSequence(this as PsiElement?) { element -> element.parent }
            .filterIsInstance<KtClassOrObject>()
            .mapNotNull(KtClassOrObject::getName)
            .toList()
            .asReversed()
            .joinToString(".")
            .takeUnless(String::isBlank)
            ?: return null

        return "$packageName.$className"
    }

    private fun collectKtFilesContaining(word: String) = linkedSetOf<KtFile>().apply {
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, searchScope, { file ->
            if (file is KtFile) this += file
            true
        }, true)
    }.toList()

    private fun String.classNameText() = removeSuffix("::class")
        .substringBefore("<")
        .substringBefore("(")
        .trim()
        .removeSuffix("?")
}