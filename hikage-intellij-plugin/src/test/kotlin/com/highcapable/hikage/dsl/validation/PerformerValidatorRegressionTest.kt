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
 * This file is created by fankes on 2026/7/24.
 */
package com.highcapable.hikage.dsl.validation

import com.highcapable.hikage.inspection.PerformerDeclarationInspection
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Verifies View, constructor, nullability, LayoutParams, and declaration annotation validation.
 */
class PerformerValidatorRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies the normalized validator result for each supported declaration failure. */
    fun testViewAndLayoutParamsValidationMatrix() {
        installHikageTestApi()
        val file = configureKotlinByText(
            "PerformerValidationMatrix.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import android.view.ViewGroup

            class ValidView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            class NonNullableView(context: Context, attrs: AttributeSet) : View(context, attrs)
            class MissingConstructorView : View()
            class NotAView
            class ValidLayoutParams : ViewGroup.LayoutParams()
            class InvalidLayoutParams
            """.trimIndent()
        )
        val classes = file.declarations.filterIsInstance<KtClassOrObject>().associateBy(KtClassOrObject::getName)
        val validator = PerformerValidator.from(project)
        val javaFacade = JavaPsiFacade.getInstance(project)
        val searchScope = GlobalSearchScope.allScope(project)

        assertEquals(
            PerformerValidator.Result.VALID,
            validator.validate(PerformerValidator.Type.VIEW, classes.getValue("ValidView"))
        )
        assertEquals(
            PerformerValidator.Result.NON_NULLABLE_ATTRIBUTE_SET,
            validator.validate(PerformerValidator.Type.VIEW, classes.getValue("NonNullableView"))
        )
        assertEquals(
            PerformerValidator.Result.MISSING_CONSTRUCTOR,
            validator.validate(PerformerValidator.Type.VIEW, classes.getValue("MissingConstructorView"))
        )
        assertEquals(
            PerformerValidator.Result.NOT_VIEW,
            validator.validate(PerformerValidator.Type.VIEW, classes.getValue("NotAView"))
        )
        assertEquals(
            PerformerValidator.Result.VALID,
            validator.validate(
                PerformerValidator.Type.LPARAMS,
                requireNotNull(javaFacade.findClass("android.view.ViewGroup.LayoutParams", searchScope))
            )
        )
        assertEquals(
            PerformerValidator.Result.NOT_LAYOUT_PARAMS,
            validator.validate(
                PerformerValidator.Type.LPARAMS,
                requireNotNull(javaFacade.findClass("android.view.View", searchScope))
            )
        )
    }

    /** Verifies invalid aliases and declaration-object ownership through the real Inspection. */
    fun testDeclarationInspectionReportsAliasAndObjectContract() {
        installHikageTestApi()
        enableHikageProject()
        myFixture.enableInspections(PerformerDeclarationInspection())
        configureKotlinByText(
            "PerformerDeclarationInspection.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView
            import com.highcapable.hikage.annotation.HikageViewDeclaration

            @HikageView(alias = "invalid-alias")
            class ValidView(context: Context, attrs: AttributeSet?) : View(context, attrs)

            @HikageViewDeclaration(view = ValidView::class)
            class InvalidDeclarationOwner
            """.trimIndent()
        )

        val descriptions = myFixture.doHighlighting().mapNotNull { highlight -> highlight.description }

        assertTrue(descriptions.any { description -> description.contains("alias") })
        assertTrue(descriptions.any { description -> description.contains("independent") })
    }
}