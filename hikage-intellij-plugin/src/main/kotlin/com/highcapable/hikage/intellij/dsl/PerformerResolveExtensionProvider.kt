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
package com.highcapable.hikage.intellij.dsl

import com.highcapable.hikage.intellij.dsl.builder.PerformerSourceBuilder
import com.highcapable.hikage.intellij.dsl.model.PerformerDeclaration
import com.highcapable.hikage.intellij.dsl.resolve.PerformerDeclarations
import com.highcapable.hikage.intellij.project.ProjectGate
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtension
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionFile
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionNavigationTargetsProvider
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.idea.base.projectStructure.KaSourceModuleKind
import org.jetbrains.kotlin.idea.base.projectStructure.KaSourceModuleWithKind
import org.jetbrains.kotlin.idea.base.projectStructure.modules.KaSourceModuleForOutsider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement

/**
 * Provides dynamic Hikage performer declarations to K2 resolve without writing generated source files.
 */
@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
class PerformerResolveExtensionProvider : KaResolveExtensionProvider() {

    override fun provideExtensionsFor(module: KaModule): List<KaResolveExtension> {
        val project = module.project
        if (!ProjectGate.from(project).isEnabled()) return emptyList()

        if (module !is KaSourceModuleWithKind) return emptyList()
        if (module is KaSourceModuleForOutsider) return emptyList()
        if (module.kind != KaSourceModuleKind.PRODUCTION && module.kind != KaSourceModuleKind.TEST) return emptyList()

        return listOf(ResolveExtension(project))
    }

    private class ResolveExtension(private val project: Project) : KaResolveExtension() {

        override fun getKtFiles() = PerformerDeclarations.resolve(project).map { declaration -> ResolveFile(declaration) }

        override fun getContainedPackages() = PerformerDeclarations.resolve(project).mapTo(mutableSetOf()) { declaration ->
            // Expose only the package that actually owns the generated top-level function.
            // Advertising `packageName.functionName` as a package makes Kotlin import resolve
            // a same-named performer as a package first, which leaves its import unresolved.
            FqName(declaration.generatedPackageName)
        }
    }

    private class ResolveFile(private val declaration: PerformerDeclaration) : KaResolveExtensionFile() {

        override fun getFileName() = "${declaration.functionName}.kt"

        override fun getFilePackageName() = FqName(declaration.generatedPackageName)

        override fun getTopLevelClassifierNames() = emptySet<Name>()

        override fun getTopLevelCallableNames() = setOf(Name.identifier(declaration.functionName))

        override fun buildFileText() = PerformerSourceBuilder.createSource(declaration)

        override fun createNavigationTargetsProvider() = NavigationTargetsProvider(declaration)
    }

    private class NavigationTargetsProvider(
        private val declaration: PerformerDeclaration
    ) : KaResolveExtensionNavigationTargetsProvider() {

        override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> {
            val project = element.project
            if (PerformerDeclarations.resolve(project).none { performer -> performer.generatedKey == declaration.generatedKey })
                return emptyList()

            val viewClass = JavaPsiFacade.getInstance(project)
                .findClass(declaration.viewClass, GlobalSearchScope.allScope(project))
                ?: return emptyList()

            return listOf(viewClass.navigationElement)
        }
    }
}