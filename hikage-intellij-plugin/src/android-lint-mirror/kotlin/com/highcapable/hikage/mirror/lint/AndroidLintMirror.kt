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
 * This file is created by fankes on 2026/7/21.
 */
package com.highcapable.hikage.mirror.lint

import com.android.SdkConstants
import com.android.resources.ResourceFolderType
import com.android.tools.idea.lint.common.LintEditorResult
import com.android.tools.idea.lint.common.LintIdeClient
import com.android.tools.idea.lint.common.LintIdeSupport
import com.android.tools.idea.model.MergedManifestManager
import com.android.tools.idea.model.MergedManifestModificationTracker
import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.LintRequest
import com.android.tools.lint.detector.api.Constraint
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.LintMap
import com.android.tools.lint.detector.api.Platform
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.TextFormat
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScannerConstants
import com.android.utils.PositionXmlParser
import com.android.utils.XmlUtils
import com.highcapable.hikage.mirror.lint.builder.LayoutSnapshotBuilder
import com.highcapable.hikage.mirror.lint.model.LayoutSnapshot.Attribute
import com.highcapable.hikage.mirror.lint.model.LayoutSnapshot.Node
import com.highcapable.hikage.mirror.lint.model.LintDetector
import com.highcapable.hikage.mirror.lint.model.LintIssue
import com.highcapable.hikage.mirror.lint.model.LintProblem
import com.highcapable.hikage.project.HikageRuntimeAttributeGate
import com.highcapable.hikage.settings.service.SettingsService
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.psi.KtFile
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.EnumSet
import java.util.concurrent.CancellationException
import org.w3c.dom.Node as DomNode

/**
 * Mirrors host Android layout Lint detectors over reconstructed Kotlin layouts.
 *
 * The mirror owns orchestration only: issue definitions and detector behavior always come from the
 * active Android Studio installation, while Kotlin-to-layout reconstruction lives in the builder package.
 */
object AndroidLintMirror {

    private const val NODE_MARKER = "data-android-lint-node"

    private val RTL_ISSUE_IDS = setOf(
        LintIssue.RTL_HARDCODED.id,
        LintIssue.RTL_COMPAT.id,
        LintIssue.RTL_SYMMETRY.id
    )

    private val CACHE_KEY = Key.create<Cache>("android.lint.mirror.problems")
    private val cacheLock = Any()

    /**
     * Returns all mirrored Android Lint problems for [file].
     *
     * Thin inspections call this entry independently, so the complete result is cached once per
     * PSI, project-root, merged-manifest, runtime-attribute, and dumb-mode state instead of repeating analysis.
     */
    fun problems(file: KtFile): List<LintProblem> {
        if (!SettingsService.getInstance(file.project).isAndroidLintMirrorEnabled || DumbService.isDumb(file.project)) return emptyList()
        val dependencies = file.currentDependencies()
        file.getUserData(CACHE_KEY)?.takeIf { cache -> cache.dependencies == dependencies }
            ?.let { cache -> return cache.problems }

        return synchronized(cacheLock) {
            val currentDependencies = file.currentDependencies()
            file.getUserData(CACHE_KEY)?.takeIf { cache -> cache.dependencies == currentDependencies }
                ?: Cache(
                    currentDependencies,
                    analyze(file, currentDependencies.runtimeAttributes)
                ).also { cache -> file.putUserData(CACHE_KEY, cache) }
        }.problems
    }

    private fun analyze(file: KtFile, isAttributeRuntimeEnabled: Boolean): List<LintProblem> = failOpen {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return@failOpen emptyList()
        val virtualFile = file.virtualFile ?: return@failOpen emptyList()
        val support = LintIdeSupport.get()
        val detectors = support.getIssueRegistry().resolveDetectors().let { detectors ->
            if (detectors.any { detector -> detector.containsRtlIssue() } && module.isRtlExplicitlyDisabled())
                detectors.filterNot { detector -> detector.containsRtlIssue() }
            else detectors
        }
        if (detectors.isEmpty()) return@failOpen emptyList()
        val applicableElements = detectors.mapNotNull { binding ->
            binding.detector.getApplicableElements()?.takeUnless { elements -> elements === XmlScannerConstants.ALL }
        }.flatten().toSet()
        val applicableAttributes = detectors.mapNotNull { binding ->
            binding.detector.getApplicableAttributes()?.takeUnless { attributes -> attributes === XmlScannerConstants.ALL }
        }.flatten().toSet()

        val snapshot = LayoutSnapshotBuilder(
            file,
            applicableElements,
            applicableAttributes,
            detectors.any { binding -> binding.detector.getApplicableAttributes() === XmlScannerConstants.ALL },
            isAttributeRuntimeEnabled
        ).build()
        if (snapshot.roots.isEmpty()) return@failOpen emptyList()

        val issues = detectors.flatMapTo(linkedSetOf(), LintDetector::issues)
        val result = LintEditorResult(module, virtualFile, file.text, issues, emptySet())
        val client = CollectingLintClient(file.project, result)
        try {
            val lintProjects = support.createProject(client, listOf(virtualFile), module)
            val lintProject = lintProjects.firstOrNull() ?: return@failOpen emptyList()
            val registry = support.getIssueRegistry(issues.toList())
            val request = LintRequest(client, emptyList())
                .setScope(Scope.RESOURCE_FILE_SCOPE)
                .setPlatform(EnumSet.of(Platform.ANDROID))
                .setProjects(lintProjects)
            val driver = client.createDriver(request, registry)
            val syntheticFile = File(lintProject.dir, "res/layout/android-lint-mirror.xml")

            snapshot.roots.flatMap { root ->
                val layout = root.toSyntheticLayout()
                // DomPsiParser treats a non-null File as an existing VFS-backed XML source and ignores the supplied
                // CharSequence. This mirror has no physical layout file, so parse the synthetic source with Lint's
                // own positioned DOM parser; the IDE parser used by XmlContext supports these nodes for locations.
                val document = failOpen {
                    PositionXmlParser.parse(layout.source)
                } ?: return@flatMap emptyList()
                val mapping = layout.bind(document)
                val context = XmlContext(
                    driver,
                    lintProject,
                    lintProject,
                    syntheticFile,
                    ResourceFolderType.LAYOUT,
                    layout.source,
                    document
                )
                detectors.flatMap { binding ->
                    client.run(binding.detector, context)
                        .mapNotNull { incident -> incident.toProblem(mapping, root) }
                }
            }
        } finally {
            client.dispose()
        }
    } ?: emptyList()

    private fun IssueRegistry.resolveDetectors(): List<LintDetector> = LintIssue.entries
        .mapNotNull { mirrorIssue -> getIssue(mirrorIssue.id) }
        .groupBy { issue -> issue.implementation.detectorClass }
        .mapNotNull { (detectorClass, issues) ->
            // Issue.Implementation exposes the authoritative detector class but no public factory. Instantiating that
            // class once per detector is the narrow compatibility workaround that avoids copying host rules and keeps
            // sibling issues on the same stateful detector execution, matching Android Lint's own visitor lifecycle.
            val detector = failOpen { detectorClass.getDeclaredConstructor().newInstance() }
                ?: return@mapNotNull null
            LintDetector(issues.toSet(), detector)
        }

    private fun LintDetector.containsRtlIssue() = issues.any { issue -> issue.id in RTL_ISSUE_IDS }

    private fun Module.isRtlExplicitlyDisabled() = failOpen {
        // LintModelModuleProject reads the Gradle model's merged-manifest file, which stays stale until Sync.
        // Android Studio's editor path uses this live merged snapshot, so use the same source for the RTL gate.
        val supplier = MergedManifestManager.getInstance(this).supplier
        val document = supplier.getOrCreateSnapshot(supplier.now).document ?: return@failOpen false
        val application = document.documentElement?.let { root ->
            XmlUtils.getFirstSubTagByName(root, SdkConstants.TAG_APPLICATION)
        } ?: return@failOpen false
        application.getAttributeNS(SdkConstants.ANDROID_URI, SdkConstants.ATTR_SUPPORTS_RTL) == SdkConstants.VALUE_FALSE
    } == true

    private fun CollectingLintClient.run(detector: Detector, context: XmlContext): List<Incident> {
        currentDetector = detector
        incidents.clear()

        return try {
            detector.beforeCheckFile(context)
            detector.visitDocument(context, context.document)
            context.document.documentElement?.visit(detector, context)
            detector.afterCheckFile(context)
            incidents.toList()
        } finally {
            currentDetector = null
        }
    }

    private fun Element.visit(detector: Detector, context: XmlContext) {
        val applicableElements = detector.getApplicableElements()
        val visitsElement = applicableElements === XmlScannerConstants.ALL || applicableElements?.contains(tagName) == true
        if (visitsElement) detector.visitElement(context, this)

        val applicableAttributes = detector.getApplicableAttributes()
        if (applicableAttributes != null) {
            val nodes = attributes
            repeat(nodes.length) { index ->
                val attribute = nodes.item(index) as? Attr ?: return@repeat
                if (applicableAttributes === XmlScannerConstants.ALL || attribute.localName in applicableAttributes)
                    detector.visitAttribute(context, attribute)
            }
        }

        val children = childNodes
        repeat(children.length) { index ->
            (children.item(index) as? Element)?.visit(detector, context)
        }
        if (visitsElement) detector.visitElementAfter(context, this)
    }

    private fun Incident.toProblem(mapping: SyntheticMapping, root: Node): LintProblem? {
        val incidentIssueId = issue.id
        val mirrorIssue = LintIssue.entries.firstOrNull { mirrorIssue -> mirrorIssue.id == incidentIssueId } ?: return null
        val sourceNode = (location.source as? DomNode) ?: scope as? DomNode ?: return null
        val element = when (sourceNode) {
            is Attr -> mapping.attributes[sourceNode]?.let { attribute ->
                if (!sourceNode.acceptsValueIncident(mirrorIssue, attribute, mapping)) return null
                when (mirrorIssue) {
                    LintIssue.CONTENT_DESCRIPTION -> attribute.valueElement ?: attribute.nameElement
                    LintIssue.HARDCODED_TEXT -> attribute.valueElement ?: attribute.nameElement
                    LintIssue.ELLIPSIZE_MAX_LINES,
                    LintIssue.INVALID_IME_ACTION_ID,
                    LintIssue.PX_USAGE,
                    LintIssue.SP_USAGE,
                    LintIssue.IN_OR_MM_USAGE,
                    LintIssue.SMALL_SP -> attribute.valueElement ?: attribute.nameElement
                    LintIssue.RTL_HARDCODED -> when (attribute.name) {
                        SdkConstants.ATTR_GRAVITY,
                        SdkConstants.ATTR_LAYOUT_GRAVITY -> attribute.valueElement ?: attribute.nameElement
                        else -> attribute.nameElement
                    }
                    else -> attribute.nameElement
                }
            }
            is Element -> mapping.nodes[sourceNode]?.let { node ->
                if (!node.acceptsMissingAttributeIncident(mirrorIssue, root)) return null
                node.element
            }
            else -> null
        } ?: return null

        return LintProblem(mirrorIssue, element, TextFormat.RAW.toHtml(message))
    }

    private fun Attr.acceptsValueIncident(issue: LintIssue, attribute: Attribute, mapping: SyntheticMapping) = when (issue) {
        LintIssue.CONTENT_DESCRIPTION -> !ownerElement.hasNonAttrsAttribute(mapping, SdkConstants.ATTR_CONTENT_DESCRIPTION)
        LintIssue.KEYBOARD_INACCESSIBLE_WIDGET -> !ownerElement.hasNonAttrsAttribute(mapping, SdkConstants.ATTR_FOCUSABLE)
        LintIssue.INVALID_IME_ACTION_ID -> attribute.isValueStatic
        LintIssue.TEXT_VIEW_EDITS -> attribute.name != SdkConstants.ATTR_INPUT_TYPE || attribute.isValueStatic
        LintIssue.ELLIPSIZE_MAX_LINES -> ownerElement.attributesAreStatic(
            mapping,
            "ellipsize",
            "lines",
            "maxLines"
        )
        LintIssue.RTL_COMPAT -> ownerElement.attributesAreStatic(
            mapping,
            SdkConstants.ATTR_TEXT_ALIGNMENT,
            SdkConstants.ATTR_GRAVITY,
            SdkConstants.ATTR_LAYOUT_GRAVITY
        )
        else -> true
    }

    private fun Element.attributesAreStatic(mapping: SyntheticMapping, vararg names: String): Boolean {
        val node = mapping.nodes[this] ?: return false
        return node.attributes.filter { attribute -> attribute.name in names }.all(Attribute::isValueStatic)
    }

    private fun Element.hasNonAttrsAttribute(mapping: SyntheticMapping, name: String) =
        mapping.nodes[this]?.hasNonAttrsAttribute(name) == true

    private fun Node.acceptsMissingAttributeIncident(issue: LintIssue, root: Node) = when (issue) {
        LintIssue.HARDCODED_TEXT,
        LintIssue.RTL_HARDCODED -> true
        LintIssue.CONTENT_DESCRIPTION -> isAttributeModelComplete &&
            !hasNonAttrsAttribute(SdkConstants.ATTR_CONTENT_DESCRIPTION)
        LintIssue.KEYBOARD_INACCESSIBLE_WIDGET -> isAttributeModelComplete
        LintIssue.LABEL_FOR -> root.flatten().all(Node::isAttributeModelComplete) && acceptsLabelForIncident()
        LintIssue.RELATIVE_OVERLAP -> root.flatten().all(Node::isAttributeModelComplete)
        LintIssue.AUTOFILL -> isAttributeModelComplete &&
            !hasNonAttrsAttribute(SdkConstants.ATTR_AUTOFILL_HINTS)
        LintIssue.TEXT_FIELDS -> isAttributeModelComplete &&
            !hasNonAttrsAttribute(SdkConstants.ATTR_INPUT_TYPE, SdkConstants.ATTR_INPUT_METHOD)
        else -> true
    }

    private fun Node.acceptsLabelForIncident() = when (tagName) {
        SdkConstants.EDIT_TEXT,
        SdkConstants.AUTO_COMPLETE_TEXT_VIEW,
        SdkConstants.MULTI_AUTO_COMPLETE_TEXT_VIEW -> !hasNonAttrsAttribute(SdkConstants.ATTR_HINT)
        else -> !hasNonAttrsAttribute(SdkConstants.ATTR_TEXT, SdkConstants.ATTR_CONTENT_DESCRIPTION)
    }

    private fun Node.hasNonAttrsAttribute(vararg names: String) =
        nonAttrsAttributeNames.any { attributeName -> attributeName in names }

    private fun Node.toSyntheticLayout(): SyntheticLayout {
        var nextId = 0
        val nodes = mutableMapOf<Int, Node>()

        val source = buildString {
            fun appendNode(node: Node) {
                val id = nextId++
                nodes[id] = node
                append('<').append(node.tagName)
                if (id == 0) append(" xmlns:android=\"").append(SdkConstants.ANDROID_URI).append('"')
                append(' ').append(NODE_MARKER).append("=\"").append(id).append('"')
                node.attributes.forEach { attribute ->
                    if (!attribute.name.isXmlName()) return@forEach
                    append(" android:").append(attribute.name).append("=\"")
                        .append(StringUtil.escapeXmlEntities(attribute.value))
                        .append('"')
                }
                if (node.children.isEmpty()) {
                    append("/>")
                    return
                }
                append('>')
                node.children.forEach(::appendNode)
                append("</").append(node.tagName).append('>')
            }

            appendNode(this@toSyntheticLayout)
        }

        return SyntheticLayout(source, nodes)
    }

    private fun SyntheticLayout.bind(document: Document): SyntheticMapping {
        val domNodes = mutableMapOf<Element, Node>()
        val domAttributes = mutableMapOf<Attr, Attribute>()

        fun bindElement(element: Element) {
            val node = element.getAttribute(NODE_MARKER).toIntOrNull()?.let(nodes::get) ?: return
            domNodes[element] = node
            node.attributes.forEach { attribute ->
                element.getAttributeNodeNS(SdkConstants.ANDROID_URI, attribute.name)?.let { domAttribute ->
                    domAttributes[domAttribute] = attribute
                }
            }
            val children = element.childNodes
            repeat(children.length) { index -> (children.item(index) as? Element)?.let(::bindElement) }
        }
        document.documentElement?.let(::bindElement)

        return SyntheticMapping(domNodes, domAttributes)
    }

    private fun Node.flatten(): Sequence<Node> = sequence {
        yield(this@flatten)
        children.forEach { child -> yieldAll(child.flatten()) }
    }

    private fun String.isXmlName() = isNotEmpty() &&
        first().let { char -> char.isLetter() || char == '_' } &&
        all { char -> char.isLetterOrDigit() || char in ".-_" }

    private fun KtFile.currentDependencies() = Dependencies(
        psi = PsiModificationTracker.getInstance(project).modificationCount,
        projectRoots = ProjectRootModificationTracker.getInstance(project).modificationCount,
        mergedManifest = ModuleUtilCore.findModuleForPsiElement(this)
            ?.let(MergedManifestModificationTracker::getInstance)
            ?.modificationCount
            ?: -1,
        dumbMode = DumbService.getInstance(project).modificationTracker.modificationCount,
        runtimeAttributes = HikageRuntimeAttributeGate.isEnabled(this)
    )

    private inline fun <T> failOpen(action: () -> T): T? = try {
        action()
    } catch (error: Exception) {
        if (error is ControlFlowException || error is CancellationException) throw error
        null
    }

    private data class Dependencies(
        val psi: Long,
        val projectRoots: Long,
        val mergedManifest: Long,
        val dumbMode: Long,
        val runtimeAttributes: Boolean
    )

    private data class Cache(
        val dependencies: Dependencies,
        val problems: List<LintProblem>
    )

    private data class SyntheticLayout(
        val source: String,
        val nodes: Map<Int, Node>
    )

    private data class SyntheticMapping(
        val nodes: Map<Element, Node>,
        val attributes: Map<Attr, Attribute>
    )

    private class CollectingLintClient(
        project: Project,
        result: LintEditorResult
    ) : LintIdeClient(project, result) {

        val incidents = mutableListOf<Incident>()
        var currentDetector: Detector? = null

        override fun report(context: Context, incident: Incident, format: TextFormat) {
            incidents += incident
        }

        override fun report(context: Context, incident: Incident, constraint: Constraint) {
            incidents += incident
        }

        override fun report(context: Context, incident: Incident, map: LintMap) {
            if (currentDetector?.filterIncident(context, incident, map) != false) incidents += incident
        }
    }
}