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
 * This file is created by fankes on 2026/7/29.
 */
package com.highcapable.hikage.convert

import com.highcapable.hikage.convert.bundle.ConversionBundle
import com.highcapable.hikage.convert.generator.PerformerSnippetRenderer
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Kind
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Severity
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.highcapable.hikage.convert.parser.XmlLayoutParser
import com.highcapable.hikage.convert.resolver.XmlLayoutModelResolver
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.settings.service.SettingsService
import com.highcapable.hikage.symbol.AndroidSymbols
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.xml.XmlFile
import org.jetbrains.android.facet.AndroidFacet

/**
 * Executes the read-only XML-to-Performer-Snippet conversion pipeline.
 */
object PerformerSnippetConverter {

    /**
     * Converts [file] using the current Android, performer, attribute, and settings snapshots.
     * @param file the revalidated Android XML layout file.
     * @return generated source and structured conversion diagnostics.
     */
    fun convert(file: XmlFile): ConversionOutcome<KotlinSnippet> {
        val parsed = XmlLayoutParser.parse(file)
        val layout = parsed.value ?: return ConversionOutcome(null, parsed.diagnostics)
        val module = ModuleUtilCore.findModuleForPsiElement(file)
        val facet = module?.let(AndroidFacet::getInstance)
            ?: return ConversionOutcome(
                value = null,
                diagnostics = parsed.diagnostics + ConversionDiagnostic(
                    severity = Severity.ERROR,
                    kind = Kind.ANDROID_MODEL_UNAVAILABLE,
                    message = ConversionBundle.message("conversion.diagnostic.androidModelUnavailable")
                )
            )

        val project = file.project
        val settings = SettingsService.getInstance(project)
        val isRuntimeAttributeEnabled = HikageRuntimeAttributeGate.isEnabled(file)
        val resolved = XmlLayoutModelResolver.resolve(
            layout = layout,
            facet = facet,
            declarations = PerformerDeclarations.resolve(project),
            rootLayoutParamsClass = AndroidSymbols.VIEW_GROUP_LAYOUT_PARAMS_CLASS,
            duplicateViewClasses = PerformerDeclarations.duplicateViewClasses(project),
            attributeResolver = AndroidAttributeResolver.from(file),
            viewAttributeOption = settings.viewConversionOption.effectiveOption(isRuntimeAttributeEnabled),
            layoutParamsOption = settings.layoutParamsConversionOption.effectiveOption(isRuntimeAttributeEnabled)
        )
        val root = resolved.value
        val diagnostics = parsed.diagnostics + resolved.diagnostics

        return ConversionOutcome(
            value = root?.takeUnless { diagnostics.any { diagnostic -> diagnostic.severity == Severity.ERROR } }
                ?.let(PerformerSnippetRenderer::render),
            diagnostics = diagnostics
        )
    }
}