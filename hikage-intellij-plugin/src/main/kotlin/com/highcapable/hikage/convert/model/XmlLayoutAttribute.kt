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
package com.highcapable.hikage.convert.model

/**
 * Represents one neutral XML attribute with namespace and source fidelity.
 * @param kind the structural attribute classification.
 * @param namespaceUri the resolved XML namespace URI.
 * @param namespacePrefix the source namespace prefix.
 * @param localName the unqualified attribute name.
 * @param qualifiedName the original qualified attribute name.
 * @param rawValue the original entity-encoded PSI attribute value.
 * @param value the XML-semantic display value used by generated Kotlin.
 * @param source the exact attribute source.
 */
data class XmlLayoutAttribute(
    val kind: Kind,
    val namespaceUri: String,
    val namespacePrefix: String,
    val localName: String,
    val qualifiedName: String,
    val rawValue: String,
    val value: String,
    val source: ConversionSource
) {

    /**
     * Structural XML attribute kinds used before planner selection.
     */
    enum class Kind {
        /** A Hikage string-ID candidate. */
        ID,

        /** An ordinary View construction attribute. */
        VIEW,

        /** A parent LayoutParams attribute. */
        LAYOUT,

        /** A preview-only tools attribute. */
        TOOLS,

        /** An inflater or binding semantic requiring a TODO. */
        SPECIAL,

        /** Metadata consumed by the XML parser itself. */
        METADATA
    }
}