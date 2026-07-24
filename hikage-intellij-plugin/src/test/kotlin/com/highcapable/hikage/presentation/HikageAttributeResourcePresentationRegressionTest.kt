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
package com.highcapable.hikage.presentation

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.android.tools.idea.res.psi.ResourceReferencePsiElement
import com.highcapable.hikage.annotator.DeprecatedHikageAttributeAnnotator
import com.highcapable.hikage.annotator.HikageAttributeResourceExternalAnnotator
import com.highcapable.hikage.documentation.HikageAttributeResourceDocumentationProvider
import com.highcapable.hikage.reference.HikageAttributeResourceReferenceContributor
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.highcapable.hikage.utils.android.AndroidResource
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.awt.Color

/**
 * Verifies the Android Studio-native attribute resource presentation boundary.
 */
class HikageAttributeResourcePresentationRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies native drawable previews remain limited to bitmaps and vector XML resources. */
    fun testNativeDrawablePreviewEligibilityRejectsNonVectorXml() {
        val bitmap = LightVirtualFile("icon.png", PlainTextFileType.INSTANCE, "")
        val vector = LightVirtualFile("icon.xml", PlainTextFileType.INSTANCE, "<vector />")
        val shape = LightVirtualFile("background.xml", PlainTextFileType.INSTANCE, "<shape />")

        assertTrue(AndroidResource.canUseNativeResourcePreview(bitmap))
        assertTrue(AndroidResource.canUseNativeResourcePreview(vector))
        assertFalse(AndroidResource.canUseNativeResourcePreview(shape))
    }

    /** Verifies deprecation styling and native reference replacement ranges exclude Kotlin quotes and prefixes. */
    fun testPresentationRangesMatchKotlinStringContent() {
        val file = configureKotlinByText(
            "AttributePresentationRanges.kt",
            """
            package sample

            val attribute = "android:textColor"
            val resource = "@string/title"
            """.trimIndent()
        )
        val expressions = file.collectDescendantsOfType<KtStringTemplateExpression>()
        val attributeExpression = expressions.first()
        val resourceExpression = expressions.last()

        val contentRangeMethod = classOf<DeprecatedHikageAttributeAnnotator>().getDeclaredMethod(
            "contentRange",
            classOf<KtExpression>()
        )
        assertTrue(contentRangeMethod.trySetAccessible())
        assertEquals(
            TextRange(attributeExpression.textRange.startOffset + 1, attributeExpression.textRange.endOffset - 1),
            contentRangeMethod.invoke(DeprecatedHikageAttributeAnnotator(), attributeExpression)
        )

        val reference = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "title")
        val resourceNameRangeMethod = classOf<HikageAttributeResourceReferenceContributor>().getDeclaredMethod(
            "resourceNameRange",
            classOf<KtStringTemplateExpression>(),
            classOf<ResourceReference>()
        )
        assertTrue(resourceNameRangeMethod.trySetAccessible())
        val range = resourceNameRangeMethod.invoke(
            HikageAttributeResourceReferenceContributor(),
            resourceExpression,
            reference
        ) as TextRange

        assertEquals("title", range.substring(resourceExpression.text))
    }

    /** Verifies Android ARGB literals are adapted to the platform color parser without channel swaps. */
    fun testAndroidColorLiteralConversionPreservesArgbChannels() {
        val method = classOf<HikageAttributeResourceExternalAnnotator>().getDeclaredMethod(
            "toAndroidColor",
            classOf<String>()
        )
        assertTrue(method.trySetAccessible())

        val color = method.invoke(HikageAttributeResourceExternalAnnotator(), "#80FF4000") as Color
        assertEquals(0x80, color.alpha)
        assertEquals(0xFF, color.red)
        assertEquals(0x40, color.green)
        assertEquals(0x00, color.blue)
        assertNull(method.invoke(HikageAttributeResourceExternalAnnotator(), "#12"))
    }

    /** Verifies unsupported drawable XML keeps a navigable native resource fallback gutter. */
    fun testNonRenderingResourceGutterKeepsNativeIdentity() {
        val expression = configureKotlinByText(
            "AttributeResourceGutter.kt",
            """
            package sample

            val resource = "@drawable/background"
            """.trimIndent()
        ).collectDescendantsOfType<KtStringTemplateExpression>().single()
        val reference = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "background")
        val rendererClass = classOf<HikageAttributeResourceExternalAnnotator>().declaredClasses.single { type ->
            type.simpleName == "NonRenderingResourceGutterIconRenderer"
        }
        val constructor = rendererClass.getDeclaredConstructor(
            classOf<PsiElement>(),
            classOf<ResourceReference>()
        )
        assertTrue(constructor.trySetAccessible())
        val renderer = constructor.newInstance(expression, reference) as GutterIconRenderer

        assertEquals(reference.resourceUrl.toString(), renderer.tooltipText)
        assertSame(ResourceReferencePsiElement.RESOURCE_ICON, renderer.icon)
    }

    /** Verifies documentation falls back to the Android resource identity when no repository declaration is available. */
    fun testDocumentationFallbackKeepsResourceIdentity() {
        val expression = configureKotlinByText(
            "AttributeResourceDocumentation.kt",
            """
            package sample

            val resource = "@string/title"
            """.trimIndent()
        ).collectDescendantsOfType<KtStringTemplateExpression>().single()
        val reference = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "title")
        val targetClass = classOf<HikageAttributeResourceDocumentationProvider>().declaredClasses.single { type ->
            type.simpleName == "ResourceDocumentationTarget"
        }
        val constructor = targetClass.getDeclaredConstructor(
            classOf<KtStringTemplateExpression>(),
            classOf<ResourceReference>()
        )
        assertTrue(constructor.trySetAccessible())
        val target = constructor.newInstance(expression, reference) as DocumentationTarget

        assertContains(requireNotNull(target.computeDocumentationHint()), "@string/title")
    }
}