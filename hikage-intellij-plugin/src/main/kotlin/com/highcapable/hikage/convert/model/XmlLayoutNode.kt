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
 * Represents one ordered node in the neutral XML layout tree.
 * @param kind the structural XML node kind.
 * @param tagName the original XML tag name.
 * @param rawClassName the tag-derived or `<view class>` View class name.
 * @param attributes the ordered non-namespace attributes.
 * @param children the ordered child tags.
 * @param source the exact tag source.
 */
data class XmlLayoutNode(
    val kind: Kind,
    val tagName: String,
    val rawClassName: String,
    val attributes: List<XmlLayoutAttribute>,
    val children: List<XmlLayoutNode>,
    val source: ConversionSource
) {

    /**
     * Structural XML node kinds recognized by the V1 parser.
     */
    enum class Kind {
        /** An ordinary View tag or `<view class>` declaration. */
        VIEW,

        /** An XML include node requiring resource-graph conversion. */
        INCLUDE,

        /** An XML merge node requiring an enclosing parent contract. */
        MERGE,

        /** A nested Data Binding layout wrapper. */
        DATA_BINDING,

        /** A Data Binding declaration block. */
        DATA,

        /** A View tag assignment child node. */
        TAG,

        /** A post-children focus request. */
        REQUEST_FOCUS,

        /** A platform Fragment inflation node. */
        FRAGMENT
    }
}