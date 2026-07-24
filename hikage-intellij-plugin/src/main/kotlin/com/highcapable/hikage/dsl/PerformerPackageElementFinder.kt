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
package com.highcapable.hikage.dsl

import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.symbol.HikageSymbols
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPackage
import com.intellij.psi.impl.file.PsiPackageImpl
import com.intellij.psi.search.GlobalSearchScope

/**
 * Exposes Hikage dynamic performer packages to Java PSI consumers.
 */
class PerformerPackageElementFinder(private val project: Project) : PsiElementFinder(), DumbAware {

    override fun findClass(qualifiedName: String, scope: GlobalSearchScope) = null
    override fun findClasses(qualifiedName: String, scope: GlobalSearchScope) = PsiClass.EMPTY_ARRAY

    override fun findPackage(qualifiedName: String): PsiPackage? {
        if (!ProjectGate.from(project).isEnabled()) return null
        if (qualifiedName != HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX &&
            !qualifiedName.startsWith("${HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX}.")
        ) return null
        if (!qualifiedName.isDynamicHikagePackage()) return null

        // K2 represents packages declared only by KaResolveExtension as KtPackage PSI. Java/UAST
        // import inspections then call AnnotationUtil on that package, but KtPackage has no
        // containing file and trips PsiUtilCore.ensureValid(). Returning the regular Java package
        // PSI here lets JavaPsiFacade resolve the package before K2 falls back to KtPackage while
        // still keeping class lookup owned by the Kotlin resolve extension.
        return PsiPackageImpl(PsiManager.getInstance(project), qualifiedName)
    }

    private fun String.isDynamicHikagePackage(): Boolean {
        if (this in HikageSymbols.HIKAGE_WIDGET_PACKAGE_PREFIX.packagePrefixes()) return true
        val packages = PerformerDeclarations.resolve(project).flatMapTo(mutableSetOf()) { declaration ->
            declaration.generatedPackageName.packagePrefixes()
        }
        return this in packages
    }

    private fun String.packagePrefixes() = split(".")
        .runningFold(emptyList<String>()) { parts, part -> parts + part }
        .drop(1).map { parts -> parts.joinToString(".") }
}