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
package com.highcapable.hikage.convert.parser

import com.highcapable.hikage.convert.bundle.ConversionBundle
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Kind
import com.highcapable.hikage.convert.model.ConversionDiagnostic.Severity
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.ConversionSource
import com.highcapable.hikage.convert.model.XmlLayout
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.convert.model.XmlLayoutNode
import com.highcapable.hikage.symbol.AndroidSymbols
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Parses Android layout XML PSI into a mode-independent neutral tree.
 */
object XmlLayoutParser {

    private const val DATA_BINDING_EXPRESSION_PREFIX = "@{"
    private const val TWO_WAY_DATA_BINDING_EXPRESSION_PREFIX = "@={"

    /**
     * Parses [file] without resolving Views, performers, resources, or output settings.
     * @param file the XML layout PSI file.
     * @return the neutral layout and ordered parser diagnostics.
     */
    fun parse(file: XmlFile): ConversionOutcome<XmlLayout> {
        val rootTag = file.rootTag ?: return ConversionOutcome(
            value = null,
            diagnostics = listOf(ConversionDiagnostic(
                severity = Severity.ERROR,
                kind = Kind.INVALID_ROOT,
                message = ConversionBundle.message("conversion.diagnostic.invalidRoot")
            ))
        )
        val session = Session(file)
        val root = session.parseRoot(rootTag)
        val layout = root?.let { node ->
            XmlLayout(
                root = node,
                sourceFileUrl = session.fileUrl,
                hasDataBindingWrapper = session.hasDataBindingWrapper,
                dataBindingDeclarationCount = session.dataBindingDeclarationCount
            )
        }

        return ConversionOutcome(layout.takeUnless { session.hasErrors }, session.diagnostics)
    }

    private class Session(file: XmlFile) {

        val fileUrl = file.virtualFile?.url ?: file.name
        val diagnostics = mutableListOf<ConversionDiagnostic>()
        var hasDataBindingWrapper = false
        var dataBindingDeclarationCount = 0
        val hasErrors get() = diagnostics.any { diagnostic -> diagnostic.severity == Severity.ERROR }

        fun parseRoot(rootTag: XmlTag): XmlLayoutNode? {
            if (rootTag.localName != "layout") return parseTag(rootTag)

            hasDataBindingWrapper = true
            val dataTags = rootTag.subTags.filter { tag -> tag.localName == "data" }
            val contentTags = rootTag.subTags.filterNot { tag -> tag.localName == "data" }
            if (contentTags.size != 1) {
                diagnostics += ConversionDiagnostic(
                    severity = Severity.ERROR,
                    kind = Kind.INVALID_ROOT,
                    message = ConversionBundle.message("conversion.diagnostic.invalidDataBindingRoot"),
                    source = rootTag.toSource()
                )
                return null
            }

            dataBindingDeclarationCount = dataTags.sumOf { tag -> tag.subTags.size }
            diagnostics += ConversionDiagnostic(
                severity = Severity.INFORMATION,
                kind = Kind.DATA_BINDING,
                message = ConversionBundle.message(
                    "conversion.diagnostic.dataBindingUnwrapped",
                    dataBindingDeclarationCount
                ),
                source = rootTag.toSource()
            )
            return parseTag(contentTags.single())
        }

        private fun parseTag(tag: XmlTag): XmlLayoutNode {
            val kind = tag.toNodeKind()
            val rawClassName = if (tag.localName == "view") tag.getAttributeValue("class")?.trim().orEmpty()
            else tag.name
            if (kind == XmlLayoutNode.Kind.VIEW && tag.localName == "view" && rawClassName.isEmpty())
                diagnostics += ConversionDiagnostic(
                    severity = Severity.ERROR,
                    kind = Kind.UNKNOWN_VIEW,
                    message = ConversionBundle.message("conversion.diagnostic.viewClassMissing"),
                    source = tag.toSource()
                )

            return XmlLayoutNode(
                kind = kind,
                tagName = tag.name,
                rawClassName = rawClassName,
                attributes = tag.attributes
                    .filterNot(XmlAttribute::isNamespaceDeclaration)
                    .map { attribute -> attribute.toLayoutAttribute(tag) },
                children = tag.subTags.map(::parseTag),
                source = tag.toSource()
            )
        }

        private fun XmlTag.toNodeKind() = when (localName) {
            "include" -> XmlLayoutNode.Kind.INCLUDE
            "merge" -> XmlLayoutNode.Kind.MERGE
            "layout" -> XmlLayoutNode.Kind.DATA_BINDING
            "data" -> XmlLayoutNode.Kind.DATA
            "tag" -> XmlLayoutNode.Kind.TAG
            "requestFocus" -> XmlLayoutNode.Kind.REQUEST_FOCUS
            "fragment" -> XmlLayoutNode.Kind.FRAGMENT
            else -> XmlLayoutNode.Kind.VIEW
        }

        private fun XmlAttribute.toLayoutAttribute(owner: XmlTag): XmlLayoutAttribute {
            val kind = when {
                owner.localName == "view" && namespace.isEmpty() && localName == "class" -> XmlLayoutAttribute.Kind.METADATA
                isAndroidAttribute("id") -> XmlLayoutAttribute.Kind.ID
                namespace == AndroidSymbols.TOOLS_NAMESPACE_URI || namespace.isEmpty() && namespacePrefix == "tools" -> XmlLayoutAttribute.Kind.TOOLS
                namespace.isEmpty() && localName == "style" -> XmlLayoutAttribute.Kind.SPECIAL
                value?.isDataBindingExpression() == true -> XmlLayoutAttribute.Kind.SPECIAL
                localName.startsWith("layout_") -> XmlLayoutAttribute.Kind.LAYOUT
                else -> XmlLayoutAttribute.Kind.VIEW
            }

            return XmlLayoutAttribute(
                kind = kind,
                namespaceUri = namespace,
                namespacePrefix = namespacePrefix,
                localName = localName,
                qualifiedName = name,
                rawValue = value.orEmpty(),
                value = displayValue ?: StringUtil.unescapeXmlEntities(value.orEmpty()),
                source = toSource()
            )
        }

        private fun XmlAttribute.isAndroidAttribute(name: String) = localName == name &&
            (namespace == AndroidSymbols.NAMESPACE_URI || namespace.isEmpty() && namespacePrefix == "android")

        private fun String.isDataBindingExpression() = startsWith(DATA_BINDING_EXPRESSION_PREFIX) ||
            startsWith(TWO_WAY_DATA_BINDING_EXPRESSION_PREFIX)

        private fun XmlTag.toSource() = ConversionSource(fileUrl, textRange)
        private fun XmlAttribute.toSource() = ConversionSource(fileUrl, textRange)
    }
}