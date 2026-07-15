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
package com.highcapable.hikage.intellij.dsl.detector

import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Resolves Android view types from Kotlin class declarations and class literals.
 */
class ViewTypeDetector private constructor(project: Project) {

    companion object {

        /**
         * Creates a detector for the given project model.
         * @param project the project model to create the detector for.
         * @return [ViewTypeDetector]
         */
        fun from(project: Project) = ViewTypeDetector(project)
    }

    private val javaFacade = JavaPsiFacade.getInstance(project)
    private val searchScope = GlobalSearchScope.allScope(project)

    /**
     * Returns whether the given declaration or class literal resolves to an Android `View`.
     * @param literal the declaration or class literal to check.
     * @return [Boolean]
     */
    fun isView(literal: KtElement) = literal.isType(
        AndroidSymbols.VIEW_CLASS,
        AndroidSymbols.VIEW_CLASS_ID
    )

    /**
     * Returns whether the given declaration or class literal resolves to an Android `ViewGroup`.
     * @param literal the declaration or class literal to check.
     * @return [Boolean]
     */
    fun isViewGroup(literal: KtElement) = literal.isType(
        AndroidSymbols.VIEW_GROUP_CLASS,
        AndroidSymbols.VIEW_GROUP_CLASS_ID
    )

    private fun KtElement.isType(className: String, classId: ClassId) = when (this) {
        is KtClassOrObject -> toLightClass()?.isType(className) == true
        is KtExpression -> runCatching {
            analyze(this@isType) {
                val classType = this@isType.expressionType as? KaClassType ?: return@analyze false
                val targetType = classType.typeArguments.singleOrNull()?.type as? KaClassType ?: return@analyze false
                targetType.isSubtypeOf(classId)
            }
        }.getOrDefault(false)
        else -> false
    }

    private fun PsiClass.isType(className: String): Boolean {
        val targetClass = javaFacade.findClass(className, searchScope) ?: return false
        return this == targetClass || isInheritor(targetClass, true)
    }
}