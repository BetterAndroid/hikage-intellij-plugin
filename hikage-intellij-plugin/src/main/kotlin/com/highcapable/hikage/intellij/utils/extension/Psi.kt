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
package com.highcapable.hikage.intellij.utils.extension

import com.android.tools.idea.codenavigation.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.toUElementOfType

/**
 * Checks whether a [PsiParameter] is nullable.
 * @return [Boolean]
 */
fun PsiParameter.isNullable(): Boolean {
    if (annotations.any { annotation -> annotation.qualifiedName == Nullable::class.qualifiedName }) return true
    if (annotations.any { annotation -> annotation.qualifiedName == NotNull::class.qualifiedName }) return false

    val ktParameter = navigationElement as? KtParameter ?: return true
    return ktParameter.typeReference?.text?.trim()?.endsWith("?") == true
}

/**
 * Checks whether a [PsiParameter] is of the specified [psiClass].
 * @param psiClass the [PsiClass] to check against.
 * @return [Boolean]
 */
fun PsiParameter.isTypeOf(psiClass: PsiClass): Boolean {
    val classType = type as? PsiClassType ?: return false
    return classType.resolve() == psiClass
}

/**
 * Returns the canonical class name represented by this PSI type without resolving its declaration.
 * @return [String] or null if the type does not represent a class.
 */
tailrec fun PsiType.canonicalClassName(): String? = when (this) {
    is PsiWildcardType -> bound?.canonicalClassName()
    is PsiClassType -> canonicalText.substringBefore('<')
    else -> null
}

/**
 * Resolves the method of a [KtCallExpression].
 * @return [PsiMethod] or null if not found.
 */
fun KtCallExpression.resolveMethod() = toUElementOfType<UCallExpression>()?.resolve()

/**
 * Adds an import to this Kotlin file when the same class or callable is not already available.
 * @param psiFactory the Kotlin PSI factory used to create the import directive.
 * @param fqName the fully qualified class or callable name to import.
 */
fun KtFile.addImport(psiFactory: KtPsiFactory, fqName: String) {
    if (!fqName.contains('.')) return
    val packageName = fqName.substringBeforeLast('.')
    if (packageFqName.asString() == packageName) return
    if (importDirectives.any { directive ->
            val importedFqName = directive.importedFqName?.asString()
            importedFqName == fqName && directive.aliasName == null ||
                directive.isAllUnder && importedFqName == packageName
        }
    ) return

    val importDirective = psiFactory.createImportDirective(ImportPath(FqName(fqName), false))
    importList?.add(importDirective) ?: addAfter(importDirective, packageDirective)
}

/**
 * Gets the attribute argument of a [KtAnnotationEntry] by its [name] or [positionalIndex].
 * Named arguments never participate in positional fallback.
 * @param name the name of the attribute to get.
 * @param positionalIndex the positional index of the attribute to get.
 * @return [KtValueArgument] or null if not found.
 */
fun KtAnnotationEntry.attributeArgument(name: String, positionalIndex: Int): KtValueArgument? {
    val arguments = valueArgumentList?.arguments ?: return null
    return arguments.firstOrNull { argument ->
        argument.getArgumentName()?.asName?.identifier == name
    } ?: arguments.filter { argument -> argument.getArgumentName() == null }.getOrNull(positionalIndex)
}