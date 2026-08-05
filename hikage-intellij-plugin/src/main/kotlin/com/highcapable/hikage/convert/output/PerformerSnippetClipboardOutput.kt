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
package com.highcapable.hikage.convert.output

import com.highcapable.hikage.convert.bundle.ConversionBundle
import com.highcapable.hikage.convert.model.ConversionDiagnostic
import com.highcapable.hikage.convert.model.ConversionOutcome
import com.highcapable.hikage.convert.model.KotlinSnippet
import com.intellij.codeInsight.editorActions.TextBlockTransferable
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Publishes Performer snippet results to the IDE clipboard and notification surface.
 */
object PerformerSnippetClipboardOutput {

    private const val NOTIFICATION_GROUP_ID = "Hikage XML Conversion"

    private data class SourceLocation(
        val fileUrl: String,
        val fileName: String,
        val lineNumber: Int
    )

    /**
     * Copies a successful [outcome] and reports every diagnostic without modifying source files.
     * @param project the current IDE project.
     * @param outcome the completed Performer snippet conversion.
     */
    fun publish(project: Project, outcome: ConversionOutcome<KotlinSnippet>) {
        val snippet = outcome.value
        if (snippet == null) {
            notify(
                project = project,
                type = NotificationType.ERROR,
                summary = ConversionBundle.message("conversion.notification.failed"),
                diagnostics = outcome.diagnostics.filter { diagnostic ->
                    diagnostic.severity == ConversionDiagnostic.Severity.ERROR
                }
            )
            return
        }

        @Suppress("UsePropertyAccessSyntax")
        CopyPasteManager.getInstance().setContents(snippet.toTransferable())
        val hasWarnings = outcome.diagnostics.any { diagnostic ->
            diagnostic.severity == ConversionDiagnostic.Severity.WARNING
        }
        notify(
            project = project,
            type = if (hasWarnings) NotificationType.WARNING else NotificationType.INFORMATION,
            summary = if (outcome.diagnostics.isEmpty())
                ConversionBundle.message("conversion.notification.copied")
            else ConversionBundle.message(
                "conversion.notification.copiedWithDiagnostics",
                outcome.diagnostics.size
            ),
            diagnostics = outcome.diagnostics
        )
    }

    private fun KotlinSnippet.toTransferable() = TextBlockTransferable(
        code,
        listOf(PerformerSnippetPasteProcessor.TransferableData(
            imports = imports.distinct().sorted(),
            unqualifiedResourceClassName = unqualifiedResourceClassName
        )),
        null
    )

    private fun notify(
        project: Project,
        type: NotificationType,
        summary: String,
        diagnostics: List<ConversionDiagnostic>
    ) {
        val details = diagnostics.toNotificationDetails()
        val content = if (details.isEmpty()) summary else "$summary<br>$details"
        Notification(
            NOTIFICATION_GROUP_ID,
            ConversionBundle.message("conversion.notification.title"),
            content,
            type
        ).notify(project)
    }

    private fun List<ConversionDiagnostic>.toNotificationDetails(): String {
        val diagnosticsWithLocations = map { diagnostic -> diagnostic to diagnostic.resolveLocation() }
        val hasMultipleFiles = diagnosticsWithLocations.mapNotNull { (_, location) -> location?.fileUrl }
            .distinct()
            .size > 1
        return diagnosticsWithLocations
            .groupBy { (diagnostic) -> Triple(diagnostic.severity, diagnostic.kind, diagnostic.message) }
            .values
            .take(3)
            .joinToString("<br>") { groupedDiagnostics ->
                StringUtil.escapeXmlEntities(groupedDiagnostics.toDisplayText(hasMultipleFiles))
            }
    }

    private fun List<Pair<ConversionDiagnostic, SourceLocation?>>.toDisplayText(hasMultipleFiles: Boolean): String {
        val message = first().first.message
        val locations = mapNotNull { (_, location) -> location }.distinct()
        if (size == 1) {
            val location = locations.singleOrNull() ?: return message
            return if (hasMultipleFiles) ConversionBundle.message(
                "conversion.notification.diagnostic.fileLine",
                location.fileName,
                location.lineNumber,
                message
            ) else ConversionBundle.message(
                "conversion.notification.diagnostic.line",
                location.lineNumber,
                message
            )
        }
        if (locations.isEmpty()) return ConversionBundle.message(
            "conversion.notification.diagnostic.occurrences",
            message,
            size
        )

        val locationText = locations.take(3).joinToString(", ") { location ->
            if (hasMultipleFiles) "${location.fileName}:${location.lineNumber}" else location.lineNumber.toString()
        } + if (locations.size > 3) "…" else ""

        return if (hasMultipleFiles) ConversionBundle.message(
            "conversion.notification.diagnostic.occurrencesAtLocations",
            message,
            size,
            locationText
        ) else ConversionBundle.message(
            "conversion.notification.diagnostic.occurrencesAtLines",
            message,
            size,
            locationText
        )
    }

    private fun ConversionDiagnostic.resolveLocation(): SourceLocation? {
        val diagnosticSource = source ?: return null
        val file = VirtualFileManager.getInstance().findFileByUrl(diagnosticSource.fileUrl)
            ?.takeIf { virtualFile -> virtualFile.isValid }
            ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = diagnosticSource.textRange.startOffset.coerceIn(0, document.textLength)

        return SourceLocation(
            fileUrl = diagnosticSource.fileUrl,
            fileName = file.name,
            lineNumber = document.getLineNumber(offset) + 1
        )
    }
}