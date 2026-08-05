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
 * Describes one user-visible semantic result from XML conversion.
 * @param severity the diagnostic severity.
 * @param kind the stable diagnostic category.
 * @param message the localized user-facing message.
 * @param source the originating XML source when available.
 */
data class ConversionDiagnostic(
    val severity: Severity,
    val kind: Kind,
    val message: String,
    val source: ConversionSource? = null
) {

    /**
     * The severity of a conversion diagnostic.
     */
    enum class Severity {
        /** Stops the requested output from being produced. */
        ERROR,

        /** Produces output while requiring user attention. */
        WARNING,

        /** Records intentionally retained or ignored source metadata. */
        INFORMATION
    }

    /**
     * Stable conversion diagnostic categories.
     */
    enum class Kind {
        /** The XML document has no convertible root. */
        INVALID_ROOT,

        /** A Data Binding wrapper was unwrapped conservatively. */
        DATA_BINDING,

        /** An XML View class could not be resolved. */
        UNKNOWN_VIEW,

        /** A resolved View has no active performer and no proven generic fallback. */
        MISSING_PERFORMER,

        /** A resolved View uses a proven generic View or ViewGroup call. */
        GENERIC_VIEW_FALLBACK,

        /** Active performer inputs disagree for the same View. */
        DUPLICATE_PERFORMER,

        /** The selected single-file output cannot represent the XML node. */
        UNSUPPORTED_NODE,

        /** A Hikage performer cannot own the requested child hierarchy. */
        UNSUPPORTED_HIERARCHY,

        /** A Hikage string ID is invalid or duplicated. */
        INVALID_ID,

        /** A preview-only tools attribute was intentionally ignored. */
        IGNORED_TOOLS_ATTRIBUTE,

        /** An attribute remains as an adjacent generated TODO. */
        TODO_ATTRIBUTE,

        /** The Android module model was unavailable during execution. */
        ANDROID_MODEL_UNAVAILABLE
    }
}