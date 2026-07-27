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
 * This file is created by fankes on 2026/7/19.
 */
package com.highcapable.hikage.annotator

import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.res.psi.ResourceReferencePsiElement
import com.android.tools.idea.ui.resourcechooser.common.ResourcePickerSources
import com.android.tools.idea.ui.resourcechooser.util.createAndShowColorPickerPopup
import com.android.tools.idea.ui.resourcechooser.util.createAndShowResourcePickerPopup
import com.highcapable.hikage.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.project.ProjectGate
import com.highcapable.hikage.utils.android.AndroidResource
import com.intellij.ide.EssentialHighlightingMode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.ColorHexUtil
import org.jetbrains.android.AndroidAnnotatorUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.awt.Color
import java.awt.Point
import java.awt.event.MouseEvent
import com.android.tools.idea.rendering.GutterIconRenderer as AndroidResourceGutterIconRenderer

/**
 * Feeds Hikage attribute resources and inline colors into Android Studio's native gutter renderers and pickers.
 */
class HikageAttributeResourceExternalAnnotator : ExternalAnnotator<
    HikageAttributeResourceExternalAnnotator.Information,
    Map<PsiElement, GutterIconRenderer>
>() {

    private companion object {

        val RESOURCE_PICKER_SOURCES = listOf(
            ResourcePickerSources.PROJECT,
            ResourcePickerSources.ANDROID,
            ResourcePickerSources.LIBRARY
        )

        val PREVIEWABLE_RESOURCE_TYPES = setOf(
            ResourceType.COLOR,
            ResourceType.DRAWABLE,
            ResourceType.MIPMAP,
            ResourceType.ATTR,
            ResourceType.MACRO
        )
    }

    /**
     * A read-action snapshot consumed by the background annotation phase.
     */
    data class Information(
        val file: PsiFile,
        val facet: AndroidFacet,
        val elements: List<ResourceElement>
    )

    /**
     * A resolved Android resource or inline color and its Hikage string host.
     */
    data class ResourceElement(
        val expression: KtStringTemplateExpression,
        val reference: ResourceReference? = null,
        val colorValue: String? = null
    )

    override fun collectInformation(
        file: PsiFile,
        editor: Editor,
        hasErrors: Boolean
    ): Information? {
        if (!ProjectGate.from(file.project).isEnabled() ||
            !HikageRuntimeAttributeGate.isEnabled(file) ||
            file !is KtFile || EssentialHighlightingMode.isEnabled()
        ) return null

        val facet = AndroidFacet.getInstance(file) ?: return null
        val elements = mutableListOf<ResourceElement>()
        val resolver = HikageAttributeContextResolver.from(file.project)
        file.accept(object : KtTreeVisitorVoid() {

            override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                super.visitStringTemplateExpression(expression)
                val reference = resolver.resolveResourceReference(expression)
                if (reference != null) {
                    if (reference.resourceType !in PREVIEWABLE_RESOURCE_TYPES) return
                    elements += ResourceElement(expression, reference = reference)
                    return
                }
                val colorValue = resolver.resolveColorValue(expression) ?: return

                elements += ResourceElement(expression, colorValue = colorValue)
            }
        })

        return Information(file, facet, elements).takeIf { information -> information.elements.isNotEmpty() }
    }

    override fun doAnnotate(information: Information): Map<PsiElement, GutterIconRenderer> {
        val configuration = AndroidAnnotatorUtil.pickConfiguration(information.file, information.facet)
            ?: return emptyMap()
        val resolver = configuration.resourceResolver

        return buildMap {
            information.elements.forEach { element ->
                val expression = element.expression
                val reference = element.reference
                if (reference == null) {
                    val color = element.colorValue?.toAndroidColor()
                        ?: return@forEach
                    put(
                        expression,
                        ReadAction.nonBlocking<GutterIconRenderer> {
                            AndroidAnnotatorUtil.ColorRenderer(
                                expression,
                                color,
                                resolver,
                                null,
                                true,
                                information.facet
                            )
                        }.executeSynchronously()
                    )
                    return@forEach
                }
                val value = when (reference.resourceType) {
                    ResourceType.ATTR -> resolver.findItemInTheme(reference)?.let(resolver::resolveResValue)
                    else -> resolver.getResolvedResource(reference)
                }
                val renderer = when (value?.resourceType) {
                    ResourceType.COLOR, ResourceType.STYLE_ITEM, ResourceType.MACRO ->
                        ReadAction.nonBlocking<GutterIconRenderer> {
                            AndroidAnnotatorUtil.ColorRenderer(
                                expression,
                                null,
                                resolver,
                                value,
                                false,
                                information.facet
                            )
                        }.executeSynchronously()
                    ResourceType.DRAWABLE, ResourceType.MIPMAP -> {
                        val resourceFile = AndroidAnnotatorUtil.resolveDrawableFile(value, resolver, information.facet)
                        if (resourceFile?.let { AndroidResource.canUseNativeResourcePreview(it) } == true)
                            AndroidResourceGutterIconRenderer(
                                expression,
                                resolver,
                                information.facet,
                                resourceFile,
                                configuration
                            )
                        else NonRenderingResourceGutterIconRenderer(expression, reference)
                    }
                    else -> NonRenderingResourceGutterIconRenderer(expression, reference)
                }

                put(expression, renderer)
            }
        }
    }

    override fun apply(file: PsiFile, annotationResult: Map<PsiElement, GutterIconRenderer>, holder: AnnotationHolder) {
        if (!HikageRuntimeAttributeGate.isEnabled(file)) return
        val facet = AndroidFacet.getInstance(file)
        val configuration = facet?.let { AndroidAnnotatorUtil.pickConfiguration(file, it) }
        val resolver = HikageAttributeContextResolver.from(file.project)
        if (facet == null || configuration == null) return

        val adaptedResult = annotationResult.mapValues { (element, renderer) ->
            val expression = element as? KtStringTemplateExpression ?: return@mapValues renderer
            val reference = resolver.resolveResourceReference(expression)
            val color = if (reference == null) resolver.resolveColorValue(expression)?.toAndroidColor() else null
            if (reference == null && color == null) return@mapValues renderer
            val picker = when (renderer) {
                is AndroidAnnotatorUtil.ColorRenderer -> Picker.COLOR
                is AndroidResourceGutterIconRenderer -> Picker.RESOURCE
                is NonRenderingResourceGutterIconRenderer -> Picker.RESOURCE
                else -> return@mapValues renderer
            }
            ResourceGutterIconRenderer(renderer, expression, reference, color, facet, configuration, picker)
        }
        adaptedResult.forEach { (element, renderer) ->
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element)
                .gutterIconRenderer(renderer)
                .create()
        }
    }

    /**
     * ColorHexUtil uses RGBA ordering for four/eight-digit values, while Android color literals use ARGB.
     * Reorder only the alpha-bearing forms, then delegate all channel parsing to the platform utility.
     */
    private fun String.toAndroidColor(): Color? {
        val digits = removePrefix("#")
        val platformValue = when (digits.length) {
            3, 6 -> digits
            4 -> digits.drop(1) + digits.first()
            8 -> digits.drop(2) + digits.take(2)
            else -> return null
        }
        return try {
            ColorHexUtil.fromHexOrNull(platformValue)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private enum class Picker {
        COLOR,
        RESOURCE
    }

    private class NonRenderingResourceGutterIconRenderer(
        private val element: PsiElement,
        private val reference: ResourceReference
    ) : GutterIconRenderer(), DumbAware {

        override fun getIcon() = ResourceReferencePsiElement.RESOURCE_ICON

        override fun getTooltipText() = reference.resourceUrl.toString()

        override fun equals(other: Any?) = this === other ||
            other is NonRenderingResourceGutterIconRenderer &&
            element == other.element &&
            reference == other.reference

        override fun hashCode() = 31 * element.hashCode() + reference.hashCode()
    }

    private class ResourceGutterIconRenderer(
        private val delegate: GutterIconRenderer,
        private val expression: KtStringTemplateExpression,
        private val reference: ResourceReference?,
        private val color: Color?,
        facet: AndroidFacet,
        configuration: Configuration,
        private val picker: Picker
    ) : GutterIconRenderer(), DumbAware {

        private val action = SelectResourceAction(expression, reference, color, facet, configuration, picker)

        override fun getIcon() = delegate.icon

        override fun getTooltipText() = delegate.tooltipText

        override fun getClickAction() = action

        override fun equals(other: Any?) = this === other ||
            other is ResourceGutterIconRenderer &&
            delegate == other.delegate &&
            expression == other.expression &&
            reference == other.reference &&
            color == other.color &&
            picker == other.picker

        override fun hashCode(): Int {
            var result = delegate.hashCode()
            result = 31 * result + expression.hashCode()
            result = 31 * result + reference.hashCode()
            result = 31 * result + color.hashCode()

            return 31 * result + picker.hashCode()
        }
    }

    private class SelectResourceAction(
        expression: KtStringTemplateExpression,
        private val reference: ResourceReference?,
        private val color: Color?,
        private val facet: AndroidFacet,
        private val configuration: Configuration,
        private val picker: Picker
    ) : AnAction(), DumbAware {

        private val project = expression.project
        private val expressionPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(expression)

        override fun actionPerformed(event: AnActionEvent) {
            if (expressionPointer.element == null) return
            val location = event.popupLocation() ?: return

            when (picker) {
                Picker.COLOR -> showColorPicker(location)
                Picker.RESOURCE -> createAndShowResourcePickerPopup(
                    ResourceType.DRAWABLE,
                    configuration,
                    facet,
                    RESOURCE_PICKER_SOURCES,
                    location,
                    ::replaceValue
                )
            }
        }

        private fun AnActionEvent.popupLocation(): Point? {
            (inputEvent as? MouseEvent)?.locationOnScreen?.let { location -> return location }
            val editor = getData(CommonDataKeys.EDITOR) ?: return null
            val editorLocation = editor.contentComponent.locationOnScreen
            val caretLocation = editor.visualPositionToXY(editor.caretModel.visualPosition)

            return Point(editorLocation.x + caretLocation.x, editorLocation.y + caretLocation.y)
        }

        private fun showColorPicker(location: Point) {
            val resolver = configuration.resourceResolver
            createAndShowColorPickerPopup(
                color,
                reference?.let(resolver::getResolvedResource),
                configuration,
                RESOURCE_PICKER_SOURCES,
                null,
                location,
                { color -> replaceValue(color.toResourceValue()) },
                ::replaceValue
            )
        }

        /**
         * Android Studio's picker formats custom colors through ResourcesUtil, which is supplied by a non-exported
         * bundled library. Keep the same RGB/ARGB output shape here without adding that implementation JAR to the
         * plugin distribution.
         */
        private fun Color.toResourceValue() = if (alpha == 255)
            "#%02X%02X%02X".format(red, green, blue)
        else "#%02X%02X%02X%02X".format(alpha, red, green, blue)

        private fun replaceValue(value: String) {
            ApplicationManager.getApplication().invokeLater(
                {
                    val expression = expressionPointer.element ?: return@invokeLater
                    val file = expression.containingFile
                    WriteCommandAction.runWriteCommandAction(
                        project,
                        "Resource Picked",
                        null,
                        Runnable {
                            val current = expressionPointer.element ?: return@Runnable
                            ElementManipulators.handleContentChange(
                                current,
                                ElementManipulators.getValueTextRange(current),
                                value
                            )
                        },
                        file
                    )
                },
                project.disposed
            )
        }
    }
}