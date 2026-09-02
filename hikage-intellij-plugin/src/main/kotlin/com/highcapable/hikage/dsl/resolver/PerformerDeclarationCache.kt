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
 * This file is created by fankes on 2026/9/2.
 */
package com.highcapable.hikage.dsl.resolver

import com.highcapable.hikage.dsl.matcher.DeclarationMatcher
import com.highcapable.hikage.dsl.model.HikageViewAnnotation
import com.highcapable.hikage.dsl.model.PerformerDeclaration
import com.highcapable.hikage.project.model.gradle.tracker.ExternalSystemModelModificationTracker
import com.highcapable.kavaref.extension.classOf
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Caches generated performer declarations against their project-local source inputs.
 */
@Service(Service.Level.PROJECT)
class PerformerDeclarationCache(private val project: Project) : SimpleModificationTracker(), Disposable {

    companion object {

        private const val JAVA_FILE_EXTENSION = "java"
        private const val KOTLIN_FILE_EXTENSION = "kt"

        /** Returns the performer declaration cache for [project]. */
        fun getInstance(project: Project) = project.service<PerformerDeclarationCache>()
    }

    private data class Dependencies(
        val projectRoots: Long,
        val declarationInputs: Long,
        val vfsStructure: Long,
        val externalProjectsData: Long,
        val externalSystemModel: Long
    )

    private data class Snapshot(
        val dependencies: Dependencies,
        val declarations: List<PerformerDeclaration>,
        val duplicateViewClasses: Set<String>
    )

    private val cacheLock = Any()

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var trackedInputFiles = emptySet<VirtualFile>()

    @Volatile
    private var referencedClassNames = emptySet<String>()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun beforeChildRemoval(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun beforeChildReplacement(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun beforeChildMovement(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun childAdded(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun childMoved(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = invalidateIfAffected(event)
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any(::isExternalDeclarationInputChange)) incModificationCount()
            }
        })
    }

    /** Returns the current generated performer declarations. */
    fun resolve() = resolveSnapshot().declarations

    /** Returns View classes that have more than one active project declaration source. */
    fun duplicateViewClasses() = resolveSnapshot().duplicateViewClasses

    private fun resolveSnapshot(): Snapshot {
        val dependencies = currentDependencies()
        snapshot?.takeIf { current -> current.dependencies == dependencies }?.let { current -> return current }

        return synchronized(cacheLock) {
            val currentDependencies = currentDependencies()
            snapshot?.takeIf { current -> current.dependencies == currentDependencies } ?: ApplicationManager.getApplication()
                .runReadAction(Computable {
                    // Resolve extensions require a synchronous snapshot. Keep the expensive read project-local and
                    // rebuild it only for files that can actually change generated performer declarations.
                    val result = PerformerDeclarationCollector.from(project, trackedInputFiles).collectResult()
                    trackedInputFiles = result.inputFiles
                    referencedClassNames = result.referencedClassNames
                    Snapshot(currentDependencies, result.declarations, result.duplicateViewClasses).also { current ->
                        snapshot = current
                    }
                })
        }
    }

    private fun currentDependencies() = Dependencies(
        projectRoots = ProjectRootModificationTracker.getInstance(project).modificationCount,
        declarationInputs = modificationCount,
        vfsStructure = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount,
        externalProjectsData = ExternalProjectsDataStorage.getInstance(project).modificationCount,
        externalSystemModel = ExternalSystemModelModificationTracker.getInstance(project).modificationCount
    )

    private fun invalidateIfAffected(event: PsiTreeChangeEvent) {
        sequenceOf(event.child, event.newChild, event.oldChild)
            .filterIsInstance<PsiFile>()
            .firstOrNull()
            ?.let { changedFile ->
                val virtualFile = changedFile.virtualFile ?: return
                if (virtualFile.canAffectDeclarationInputs()) incModificationCount()
                return
            }
        val changedElements = event.changedElements().toList()
        val file = event.file ?: changedElements.firstNotNullOfOrNull { element ->
            element as? PsiFile ?: element.containingFile
        } ?: return

        val virtualFile = file.virtualFile
        val tracked = virtualFile?.let(trackedInputFiles::contains) == true
        val annotation = file is KtFile && event.introducesAnnotationInput(file)
        val referencedClass = event.introducesReferencedClass()

        if (annotation && virtualFile != null) trackedInputFiles = trackedInputFiles + virtualFile
        if (tracked || annotation || referencedClass) incModificationCount()
    }

    private fun PsiTreeChangeEvent.introducesAnnotationInput(file: KtFile): Boolean {
        val changedElements = sequenceOf(parent, child, oldChild, newChild).filterNotNull().toList()
        val annotationSyntaxChanged = changedElements.any { element ->
            element.parentsWithSelf().takeWhile { parent -> parent !is KtFile }
                .any { parent -> parent is KtAnnotationEntry || parent is KtImportDirective }
        } || changedElements.any { element -> element is KtFile } ||
            sequenceOf(child, newChild).filterNotNull()
                .filter { element -> element is KtClassOrObject || element is KtFile }
                .any { element -> element.collectDescendantsOfType<KtAnnotationEntry>().isNotEmpty() }

        return annotationSyntaxChanged && file.isHikageDeclarationInput()
    }

    private fun isExternalDeclarationInputChange(event: VFileEvent): Boolean {
        val virtualFile = event.file ?: return false
        if (virtualFile in trackedInputFiles) return !event.isFromSave
        if (!virtualFile.isValid || !ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return false
        if (event.isFromSave) return false

        return virtualFile.extension == KOTLIN_FILE_EXTENSION || referencedClassNames.isNotEmpty() && virtualFile.extension == JAVA_FILE_EXTENSION
    }

    private fun VirtualFile.canAffectDeclarationInputs() = this in trackedInputFiles ||
        extension == KOTLIN_FILE_EXTENSION || referencedClassNames.isNotEmpty() && extension == JAVA_FILE_EXTENSION

    private fun KtFile.isHikageDeclarationInput() = collectDescendantsOfType<KtClassOrObject>().any { declaration ->
        declaration.annotationEntries.any { annotation ->
            HikageViewAnnotation.entries.any { definition ->
                DeclarationMatcher.isHikageAnnotation(annotation, definition.fqName)
            }
        }
    }

    private fun PsiTreeChangeEvent.introducesReferencedClass() =
        referencedClassNames.isNotEmpty() && sequenceOf(parent, child, oldChild, newChild)
            .filterNotNull()
            .flatMap { element -> element.parentsWithSelf().takeWhile { parent -> parent !is PsiFile } }
            .plus(sequenceOf(parent, child, newChild).filterIsInstance<PsiFile>())
            .flatMap { element -> element.declaredClassNames() }
            .any(referencedClassNames::contains)

    private fun PsiElement.declaredClassNames() = when (this) {
        is KtClassOrObject -> listOfNotNull(fqName?.asString()).asSequence()
        is PsiClass -> listOfNotNull(qualifiedName).asSequence()
        is KtFile -> collectDescendantsOfType<KtClassOrObject>().asSequence().mapNotNull { declaration ->
            declaration.fqName?.asString()
        }
        is PsiFile -> PsiTreeUtil.findChildrenOfType(this, classOf<PsiClass>()).asSequence().mapNotNull(PsiClass::getQualifiedName)
        else -> emptySequence()
    }

    private fun PsiTreeChangeEvent.changedElements() = sequenceOf(parent, child, newChild, oldChild).filterNotNull()

    private fun PsiElement.parentsWithSelf() = generateSequence(this) { element -> element.parent }

    override fun dispose() = Unit
}