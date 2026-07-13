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
package com.highcapable.hikage.intellij.dsl.validation

import com.highcapable.hikage.intellij.model.AndroidSymbols
import com.highcapable.hikage.intellij.utils.extension.isNullable
import com.highcapable.hikage.intellij.utils.extension.isTypeOf
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Checks whether a view class can be instantiated by Hikage generated performer code.
 */
class ViewConstructorValidator(private val project: Project) {

    private val javaFacade get() = JavaPsiFacade.getInstance(project)
    private val searchScope get() = GlobalSearchScope.allScope(project)

    /**
     * Validates a Kotlin class declaration annotated with `@HikageView`.
     */
    fun validate(ktClass: KtClassOrObject): Result {
        val psiClass = ktClass.toLightClass()
            ?: return if (ktClass.superTypeListEntries.isEmpty()) Result.NOT_VIEW else Result.RESOLUTION_FAILED
        return validate(psiClass)
    }

    /**
     * Validates the class literal used by `@HikageViewDeclaration` through K2 Analysis API.
     */
    fun validate(classLiteral: KtExpression): Result = runCatching {
        analyze(classLiteral) {
            val classType = classLiteral.expressionType as? KaClassType ?: return@analyze Result.RESOLUTION_FAILED
            val targetType = classType.typeArguments.singleOrNull()?.type as? KaClassType
                ?: return@analyze Result.RESOLUTION_FAILED
            val targetSymbol = targetType.symbol as? KaClassSymbol ?: return@analyze Result.RESOLUTION_FAILED
            if (targetType.classId == AndroidSymbols.VIEW_GROUP_CLASS_ID ||
                !targetType.isSubtypeOf(AndroidSymbols.VIEW_CLASS_ID)
            ) return@analyze Result.NOT_VIEW

            // K2 exposes Java platform types as flexible types, so their constructor parameter
            // types cannot be matched reliably through KaClassType. Java PSI preserves the
            // declared constructor shape and already models reference parameters as nullable.
            (targetSymbol.psi as? PsiClass)?.let { psiClass -> return@analyze validate(psiClass) }

            val matchingConstructors = targetSymbol.declaredMemberScope.constructors.filter { constructor ->
                val parameters = constructor.valueParameters
                parameters.size >= 2 &&
                    (parameters[0].returnType as? KaClassType)?.classId == AndroidSymbols.CONTEXT_CLASS_ID &&
                    (parameters[1].returnType as? KaClassType)?.classId == AndroidSymbols.ATTRIBUTE_SET_CLASS_ID &&
                    parameters.drop(2).all { parameter -> parameter.hasDefaultValue }
            }.toList()
            if (matchingConstructors.isEmpty()) return@analyze Result.MISSING_CONSTRUCTOR

            if (matchingConstructors.any { constructor ->
                    constructor.valueParameters[1].returnType.isMarkedNullable
                }
            ) Result.VALID else Result.NON_NULLABLE_ATTRIBUTE_SET
        }
    }.getOrDefault(Result.RESOLUTION_FAILED)

    private fun validate(psiClass: PsiClass): Result {
        val viewClass = javaFacade.findClass(AndroidSymbols.VIEW, searchScope) ?: return Result.RESOLUTION_FAILED
        if (psiClass == javaFacade.findClass(AndroidSymbols.VIEW_GROUP, searchScope)) return Result.NOT_VIEW
        if (psiClass != viewClass && !psiClass.isInheritor(viewClass, true)) return Result.NOT_VIEW

        val contextClass = javaFacade.findClass(AndroidSymbols.CONTEXT, searchScope) ?: return Result.RESOLUTION_FAILED
        val attributeSetClass = javaFacade.findClass(AndroidSymbols.ATTRIBUTE_SET, searchScope) ?: return Result.RESOLUTION_FAILED
        val matchingConstructors = psiClass.constructors.asSequence().filter { constructor ->
            val parameters = constructor.parameterList.parameters
            parameters.size >= 2 &&
                parameters[0].isTypeOf(contextClass) &&
                parameters[1].isTypeOf(attributeSetClass) &&
                parameters.drop(2).all { parameter -> parameter.hasHikageOptionalDefaultValue() }
        }.toList()
        if (matchingConstructors.isEmpty()) return Result.MISSING_CONSTRUCTOR

        return if (matchingConstructors.any { constructor -> constructor.parameterList.parameters[1].isNullable() }) {
            Result.VALID
        } else Result.NON_NULLABLE_ATTRIBUTE_SET
    }

    private fun PsiParameter.hasHikageOptionalDefaultValue(): Boolean {
        val navigationElementText = navigationElement.text ?: return false
        return navigationElementText.contains("=")
    }

    /**
     * The validation result for a Hikage view class.
     */
    enum class Result {
        /** The class satisfies Hikage KSP performer generation requirements. */
        VALID,

        /** The class could not be resolved in the current project model. */
        RESOLUTION_FAILED,

        /** The class is not an Android `View`, or is directly `ViewGroup`. */
        NOT_VIEW,

        /** The class has no compatible `(Context, AttributeSet?)` constructor. */
        MISSING_CONSTRUCTOR,

        /** The compatible constructor has a non-null `AttributeSet` parameter. */
        NON_NULLABLE_ATTRIBUTE_SET;

        /** Returns true when this result can generate a performer. */
        val isValid get() = this == VALID
    }
}