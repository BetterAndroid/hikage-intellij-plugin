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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.intellij.inspection

import com.highcapable.hikage.intellij.project.HikageProjectService
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Suppresses Kotlin naming warnings for Hikage DSL declarations.
 */
class HikageInspectionSuppressor : InspectionSuppressor {

    private companion object {
        val HIKAGE_PROPERTY_TOOL_IDS = setOf("PropertyName", "PrivatePropertyName")
        val SUPPRESSED_TOOL_IDS = setOf("FunctionName") + HIKAGE_PROPERTY_TOOL_IDS
    }

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in SUPPRESSED_TOOL_IDS) return false
        if (element.language != KotlinLanguage.INSTANCE) return false
        if (!HikageProjectService.getInstance(element.project).isHikageProject()) return false
        val declaration = element.parentOfType<KtCallableDeclaration>(withSelf = true) ?: return false
        if (HikageDeclarationMatcher.isHikagableFunction(declaration)) return true

        return toolId in HIKAGE_PROPERTY_TOOL_IDS && (declaration as? KtProperty)?.let(HikageDeclarationMatcher::isHikageProperty) == true
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String) = emptyArray<SuppressQuickFix>()
}