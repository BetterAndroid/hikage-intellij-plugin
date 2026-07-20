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
package com.highcapable.hikage.intellij.completion

import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.resources.ResourceResolver
import com.android.resources.ResourceType
import com.android.tools.idea.rendering.GutterIconCache
import com.highcapable.hikage.intellij.analysis.HikageAttributeContextResolver
import com.highcapable.hikage.intellij.analysis.HikageAttributeContextResolver.AttributeScopes
import com.highcapable.hikage.intellij.completion.decorator.HikageAttributeSetLookupDecorator
import com.highcapable.hikage.intellij.model.HikageSymbols
import com.highcapable.hikage.intellij.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.intellij.project.ProjectGate
import com.highcapable.hikage.intellij.project.model.android.AndroidAttributeResolver
import com.highcapable.hikage.intellij.utils.extension.canUseNativeResourcePreview
import com.highcapable.kavaref.extension.classOf
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.SuspendingLookupElementRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IconUtil
import org.jetbrains.android.AndroidAnnotatorUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import javax.swing.Icon

/**
 * Completes Hikage attribute setter calls, names, theme references, resources, and finite values.
 */
class HikageAttributeCompletionContributor : CompletionContributor() {

    private companion object {

        const val ANDROID_NAMESPACE = "android"
        const val APP_NAMESPACE = "app"
        const val ATTRIBUTE_PRIORITY = 1000.0
        const val RESOURCE_PREVIEW_ICON_MAX_SIZE = 16

        val BOOLEAN_VALUES = listOf("true", "false")

        fun Icon.constrainResourcePreviewSize() = IconUtil.downscaleIconToSize(
            this,
            RESOURCE_PREVIEW_ICON_MAX_SIZE,
            RESOURCE_PREVIEW_ICON_MAX_SIZE
        )
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val isProjectEnabled = ProjectGate.from(parameters.position.project).isEnabled()
        if (parameters.completionType != CompletionType.BASIC ||
            parameters.position.language != KotlinLanguage.INSTANCE ||
            !isProjectEnabled
        ) {
            super.fillCompletionVariants(parameters, result)
            return
        }

        val literal = parameters.findStringLiteral()
        if (literal == null) {
            if (HikageRuntimeAttributeGate.isEnabled(parameters.position) &&
                result.prefixMatcher.prefix.isHikageAttributeSetPrefix()
            ) completeAttributeSetCall(parameters, result)
            else super.fillCompletionVariants(parameters, result)
            return
        }

        super.fillCompletionVariants(parameters, result)

        val contextResolver = HikageAttributeContextResolver.from(parameters.position.project)
        val setCall = contextResolver.resolveSetCall(literal.callExpression) ?: return
        if (literal.argument !== setCall.nameArgument && literal.argument !== setCall.valueArgument) return
        val attributeResolver = AndroidAttributeResolver.from(literal.expression)
        if (attributeResolver == null) {
            result.stopHere()
            return
        }
        val scopes = contextResolver.resolveScopes(setCall)

        when (literal.argument) {
            setCall.nameArgument -> completeAttributeName(
                result,
                literal.contentBeforeCaret,
                literal.contentAfterCaret,
                setCall,
                scopes,
                attributeResolver
            )
            setCall.valueArgument -> completeAttributeValue(
                result,
                literal.contentBeforeCaret,
                literal.contentAfterCaret,
                setCall,
                contextResolver,
                scopes,
                attributeResolver
            )
        }
        result.stopHere()
    }

    private fun completeAttributeSetCall(parameters: CompletionParameters, result: CompletionResultSet) {
        result.runRemainingContributors(parameters, false).forEach { completionResult ->
            val lookupElement = HikageAttributeSetLookupDecorator.decorateIfNeeded(completionResult.lookupElement)
            result.passResult(completionResult.withLookupElement(lookupElement))
        }
        result.stopHere()
    }

    private fun String.isHikageAttributeSetPrefix() = isNotEmpty() && HikageSymbols.HIKAGE_ATTRIBUTE_SET_FUNCTION_NAME.startsWith(this)

    private fun completeAttributeName(
        result: CompletionResultSet,
        content: String,
        suffix: String,
        setCall: HikageAttributeContextResolver.SetCall,
        scopes: AttributeScopes?,
        resolver: AndroidAttributeResolver
    ) {
        setCall.namespace?.let { namespace ->
            if (':' in content || ':' in suffix) return
            val attributes = resolver.attributes(namespace, scopes?.view, scopes?.layout)
            result.withPrefixMatcher(content).addAllElements(
                attributes.map { attribute ->
                    attributeNameLookup(attribute, attribute.name, suffix, setCall)
                }
            )
            return
        }

        val separator = content.indexOf(':')
        if (separator >= 0) {
            if (separator == 0 || content.indexOf(':', separator + 1) >= 0) return
            val namespace = content.substring(0, separator)
            val prefix = content.substring(separator + 1)
            val attributes = resolver.attributes(namespace, scopes?.view, scopes?.layout)
            result.withPrefixMatcher(prefix).addAllElements(
                attributes.map { attribute ->
                    attributeNameLookup(attribute, attribute.name, suffix, setCall)
                }
            )
            return
        }

        val attributesByNamespace = listOf(ANDROID_NAMESPACE, APP_NAMESPACE).associateWith { namespace ->
            resolver.attributes(namespace, scopes?.view, scopes?.layout)
        }
        val lookups = attributesByNamespace.flatMap { (namespace, attributes) ->
            attributes.map { attribute ->
                attributeNameLookup(attribute, "$namespace:${attribute.name}", suffix, setCall)
                    .withLookupString(attribute.name)
            }
        }
        result.withPrefixMatcher(content).addAllElements(lookups)
    }

    private fun completeAttributeValue(
        result: CompletionResultSet,
        content: String,
        suffix: String,
        setCall: HikageAttributeContextResolver.SetCall,
        contextResolver: HikageAttributeContextResolver,
        scopes: AttributeScopes?,
        resolver: AndroidAttributeResolver
    ) {
        val attributeName = contextResolver.resolveAttributeName(setCall)
        val resolution = if (attributeName == null) null
        else resolver.resolve(attributeName.namespace, attributeName.name, scopes?.layout)
        val attribute = when (resolution) {
            is AndroidAttributeResolver.Resolution.Found -> resolution.attribute
            else -> null
        } ?: return

        val previewProvider = (setCall.valueArgument?.getArgumentExpression() as? KtStringTemplateExpression)
            ?.resourcePreviewProvider()

        if (content.startsWith('?')) {
            if (resolver.acceptedResourceTypes(attribute).isEmpty()) return
            val references = resolver.attributeReferences()
            result.withPrefixMatcher(content).addAllElements(
                references.map { reference ->
                    val lookup = attributeLookup(reference.attribute, reference.text)
                        .replaceSuffix(suffix)
                        .withLookupString(reference.attribute.name)
                        .withLookupString("?${reference.attribute.name}")
                    previewProvider?.decorate(lookup, reference.attribute.definition.resourceReference) ?: lookup
                }
            )
            return
        }
        if (content.startsWith('@')) {
            completeResourceReference(result, content, suffix, attribute, resolver, previewProvider)
            return
        }

        completeFiniteValues(result, content, suffix, attribute)
        if (resolver.hasExpandedResourceCompletion(attribute))
            completeResourceReferences(result, content, suffix, attribute, resolver, previewProvider)
    }

    private fun completeFiniteValues(
        result: CompletionResultSet,
        content: String,
        suffix: String,
        attribute: AndroidAttributeResolver.Attribute
    ) {
        val values = attribute.completionValues()
        if (values.isEmpty()) return

        val isFlags = AttributeFormat.FLAGS in attribute.formats
        if (!isFlags && '|' in content) return
        val prefix = content.substringAfterLast('|').trimStart()
        val usedValues = listOf(
            content.substringBeforeLast('|', missingDelimiterValue = ""),
            suffix.substringAfter('|', missingDelimiterValue = "")
        ).flatMap { segment -> segment.split('|') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val declaredValues = attribute.values.toSet()
        val lookups = values
            .filterNot { value -> value in usedValues }
            .map { value ->
                LookupElementBuilder.create(value)
                    .withTailText(
                        value.takeIf { candidate -> candidate in declaredValues }
                            ?.let(attribute::valueDescription)
                            ?.let { description -> " — $description" },
                        true
                    )
                    .withStrikeoutness(
                        value in declaredValues && attribute.definition.isValueDeprecated(value)
                    )
                    .replaceSuffix(if (isFlags) suffix.substringBefore('|') else suffix)
                    .prioritized()
            }
        result.withPrefixMatcher(prefix).addAllElements(lookups)
    }

    private fun completeResourceReference(
        result: CompletionResultSet,
        content: String,
        suffix: String,
        attribute: AndroidAttributeResolver.Attribute,
        resolver: AndroidAttributeResolver,
        previewProvider: ResourcePreviewProvider?
    ) {
        if (content.startsWith("@+")) return
        val allowedTypes = resolver.acceptedResourceTypes(attribute)
        if (allowedTypes.isEmpty()) return

        val body = content.removePrefix("@")
        val slash = body.indexOf('/')
        if (slash >= 0 && body.indexOf('/', slash + 1) >= 0) return
        val colon = body.indexOf(':')
        if (colon >= 0 && (colon == 0 || body.indexOf(':', colon + 1) >= 0 || slash in 0..<colon)) return
        val namespace = colon.takeIf { index -> index >= 0 }?.let { index -> body.substring(0, index) }
        val resourceBody = if (colon >= 0) body.substring(colon + 1) else body
        val resourceSlash = resourceBody.indexOf('/')
        if (resourceSlash >= 0) {
            val type = ResourceType.fromXmlValue(resourceBody.substring(0, resourceSlash)) ?: return
            if (type !in allowedTypes) return
        }

        completeResourceReferences(result, content, suffix, attribute, resolver, previewProvider, namespace)
    }

    private fun completeResourceReferences(
        result: CompletionResultSet,
        content: String,
        suffix: String,
        attribute: AndroidAttributeResolver.Attribute,
        resolver: AndroidAttributeResolver,
        previewProvider: ResourcePreviewProvider?,
        namespace: String? = null
    ) {
        val lookups = resolver.resourceReferences(attribute, namespace).map { reference ->
            val lookup = LookupElementBuilder.create(reference.text)
                .withLookupString(reference.name)
                .withTypeText(reference.type.displayName, true)
                .replaceSuffix(suffix)
            (previewProvider?.decorate(lookup, reference.reference) ?: lookup).prioritized()
        }.toMutableList()
        if (namespace == null && content.canCompleteAndroidResourceNamespace())
            lookups += LookupElementBuilder.create("@android:")
                .replaceSuffixAndContinue(suffix.throughDelimiter(':'))
                .prioritized()
        result.withPrefixMatcher(content).addAllElements(lookups)
    }

    private fun attributeLookup(
        attribute: AndroidAttributeResolver.Attribute,
        insertion: String
    ): LookupElementBuilder {
        val formats = attribute.formats.joinToString("|") { format -> format.getName() }
        return LookupElementBuilder.create(insertion)
            .withTypeText(formats, true)
            .withTailText(attribute.ownerStyleable?.let { styleable -> "  $styleable" }, true)
            .withStrikeoutness(attribute.definition.isAttributeDeprecated)
    }

    private fun attributeNameLookup(
        attribute: AndroidAttributeResolver.Attribute,
        insertion: String,
        suffix: String,
        setCall: HikageAttributeContextResolver.SetCall
    ): LookupElementBuilder {
        val lookup = attributeLookup(attribute, insertion).withAttributeIcon()
        if (!setCall.canInsertValueArgument()) return lookup.replaceSuffix(suffix)

        return lookup.withInsertHandler { context, _ ->
            context.deleteSuffix(suffix)
            context.insertValueArgument(setCall)
        }
    }

    private fun LookupElementBuilder.replaceSuffix(suffix: String): LookupElementBuilder {
        if (suffix.isEmpty()) return this
        return withInsertHandler { context, _ -> context.deleteSuffix(suffix) }
    }

    private fun LookupElementBuilder.replaceSuffixAndContinue(suffix: String) = withInsertHandler { context, _ ->
        context.deleteSuffix(suffix)
        context.scheduleAutoPopup()
    }

    private fun InsertionContext.deleteSuffix(suffix: String) {
        if (suffix.isEmpty()) return

        val start = tailOffset
        val end = start + suffix.length
        if (end <= document.textLength &&
            document.charsSequence.subSequence(start, end).toString() == suffix
        ) document.deleteString(start, end)
    }

    private fun HikageAttributeContextResolver.SetCall.canInsertValueArgument(): Boolean {
        val argumentList = expression.valueArgumentList ?: return false
        return valueArgument == null && argumentList.rightParenthesis != null &&
            argumentList.arguments.singleOrNull() === nameArgument
    }

    private fun InsertionContext.insertValueArgument(setCall: HikageAttributeContextResolver.SetCall) {
        commitDocument()
        val call = setCall.expression.takeIf { expression -> expression.isValid }
            ?: file.findElementAt(startOffset)?.let { element ->
                PsiTreeUtil.getParentOfType(element, classOf<KtCallExpression>(), false)
            }
            ?: return
        val argumentList = call.valueArgumentList ?: return
        val nameArgument = argumentList.arguments.singleOrNull() ?: return
        if (argumentList.rightParenthesis == null) return

        val argumentText = if (nameArgument.getArgumentName() == null) "\"\"" else "value = \"\""
        val valueArgument = KtPsiFactory.contextual(call).createArgument(argumentText)
        val addedArgument = argumentList.addArgumentAfter(valueArgument, nameArgument)

        commitDocument()
        val valueExpression = addedArgument.getArgumentExpression() as? KtStringTemplateExpression ?: return
        val valueOffset = valueExpression.textRange.startOffset + 1
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(valueOffset)
        tailOffset = valueOffset
        setAddCompletionChar(false)
        scheduleAutoPopup()
    }

    private fun InsertionContext.scheduleAutoPopup() {
        val previousLaterRunnable = laterRunnable
        setLaterRunnable {
            previousLaterRunnable?.run()
            if (!project.isDisposed && !editor.isDisposed)
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
    }

    private fun AndroidAttributeResolver.Attribute.completionValues() =
        (values + if (AttributeFormat.BOOLEAN in formats) BOOLEAN_VALUES else emptyList()).distinct()

    private fun LookupElementBuilder.withAttributeIcon() = withIcon(AllIcons.Nodes.ObjectTypeAttribute)

    private fun String.throughDelimiter(delimiter: Char): String {
        val index = indexOf(delimiter)
        return if (index < 0) this else substring(0, index + 1)
    }

    private fun String.canCompleteAndroidResourceNamespace(): Boolean {
        if (isEmpty()) return true
        if (!startsWith('@')) return false

        val body = removePrefix("@")
        return ':' !in body && '/' !in body && ANDROID_NAMESPACE.startsWith(body)
    }

    private fun LookupElement.prioritized() = PrioritizedLookupElement.withPriority(this, ATTRIBUTE_PRIORITY)

    private fun KtStringTemplateExpression.resourcePreviewProvider(): ResourcePreviewProvider? {
        val facet = AndroidFacet.getInstance(this) ?: return null
        val configuration = AndroidAnnotatorUtil.pickConfiguration(containingFile.originalFile, facet) ?: return null
        return ResourcePreviewProvider(this, facet, configuration.resourceResolver)
    }

    private fun CompletionParameters.findStringLiteral(): StringLiteral? {
        val file = originalFile as? KtFile ?: return null
        val caretOffset = editor.caretModel.offset
        val candidateOffsets = listOf(caretOffset - 1, caretOffset).filter { candidate -> candidate >= 0 }
        val expression = candidateOffsets.firstNotNullOfOrNull { candidate ->
            file.findElementAt(candidate)?.let { element ->
                PsiTreeUtil.getParentOfType(element, classOf<KtStringTemplateExpression>(), false)
            }
        } ?: return null
        val source = expression.text
        if (source.length < 2 || !source.startsWith('"') || !source.endsWith('"') ||
            source.startsWith("\"\"\"") || source.contains('$')
        ) return null

        val contentStart = expression.textRange.startOffset + 1
        val contentEnd = expression.textRange.endOffset - 1
        if (caretOffset !in contentStart..contentEnd) return null
        val content = editor.document.getText(TextRange(contentStart, caretOffset))
        val suffix = editor.document.getText(TextRange(caretOffset, contentEnd))
        if ('\\' in content || '\\' in suffix) return null

        val argument = generateSequence(expression.parent) { it.parent }
            .filterIsInstance<KtValueArgument>()
            .firstOrNull()
            ?: return null
        val call = generateSequence(argument.parent) { it.parent }
            .filterIsInstance<KtCallExpression>()
            .firstOrNull()
            ?: return null
        return StringLiteral(expression, argument, call, content, suffix)
    }

    private data class StringLiteral(
        val expression: KtStringTemplateExpression,
        val argument: KtValueArgument,
        val callExpression: KtCallExpression,
        val contentBeforeCaret: String,
        val contentAfterCaret: String
    )

    private class ResourcePreviewProvider(
        private val expression: KtStringTemplateExpression,
        private val facet: AndroidFacet,
        private val resolver: ResourceResolver
    ) {

        fun decorate(lookup: LookupElementBuilder, reference: ResourceReference): LookupElement {
            val value = when (reference.resourceType) {
                ResourceType.ATTR -> resolver.findItemInTheme(reference)?.let(resolver::resolveResValue)
                ResourceType.COLOR, ResourceType.DRAWABLE, ResourceType.MIPMAP, ResourceType.MACRO ->
                    resolver.getResolvedResource(reference)
                else -> return lookup
            }
            return when (value?.resourceType) {
                ResourceType.COLOR, ResourceType.STYLE_ITEM, ResourceType.MACRO -> lookup.withIcon(
                    AndroidAnnotatorUtil.ColorRenderer(expression, null, resolver, value, false, facet)
                        .icon.constrainResourcePreviewSize()
                )
                ResourceType.DRAWABLE, ResourceType.MIPMAP -> {
                    val file = AndroidAnnotatorUtil.resolveDrawableFile(value, resolver, facet)
                        ?.takeIf(VirtualFile::canUseNativeResourcePreview)
                        ?: return lookup
                    val cachedIcon = GutterIconCache.getInstance(expression.project).getIconIfCached(file)
                    if (cachedIcon == null) DrawableResourceLookupElement(lookup, file, resolver, facet)
                    else lookup.withIcon(cachedIcon.constrainResourcePreviewSize())
                }
                else -> lookup
            }
        }
    }

    private class DrawableResourceLookupElement(
        original: LookupElement,
        private val file: VirtualFile,
        private val resolver: ResourceResolver,
        private val facet: AndroidFacet
    ) : LookupElementDecorator<LookupElement>(original) {

        override fun getExpensiveRenderer() = object :
            SuspendingLookupElementRenderer<DrawableResourceLookupElement>() {

            override suspend fun renderElementSuspending(
                element: DrawableResourceLookupElement,
                presentation: LookupElementPresentation
            ) {
                readAction { element.renderElement(presentation) }
                GutterIconCache.getInstance(element.facet.module.project)
                    .getIcon(element.file, element.resolver, element.facet)
                    ?.constrainResourcePreviewSize()
                    ?.let(presentation::setIcon)
            }
        }
    }
}