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
package com.highcapable.hikage.dsl

import com.highcapable.hikage.dsl.resolver.PerformerDeclarationCache
import com.highcapable.hikage.dsl.resolver.PerformerDeclarations
import com.highcapable.hikage.gradle.model.DefaultHikageGradleModel
import com.highcapable.hikage.gradle.model.HikageGradleModel
import com.highcapable.hikage.project.model.gradle.descriptor.HikageGradleToolingModel
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Verifies K2 resolve-extension creation against project and owning-module gates.
 */
@OptIn(KaExperimentalApi::class)
class PerformerResolveExtensionRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies expression-bodied layout typing does not invalidate unrelated performer declarations. */
    fun testDeclarationSnapshotIgnoresUnrelatedExpressionBodyEdits() {
        installHikageTestApi()
        enableHikageProject()
        storeGradleModels(owningModel = model())
        addProjectFile(
            "sample/CachedView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            import com.highcapable.hikage.annotation.HikageView

            @HikageView
            class CachedView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        addProjectFile(
            "com/highcapable/hikage/core/base/HikageView.kt",
            """
            package com.highcapable.hikage.core.base

            open class HikageView<V>
            """.trimIndent()
        )
        configureKotlinByText(
            "MainLayout.kt",
            """
            package sample

            import com.highcapable.hikage.core.base.Hikagable
            import com.highcapable.hikage.core.base.HikageView

            interface LayoutBuilder {
                fun build(): Any
            }

            class MainLayout : LayoutBuilder {
                private val view: HikageView<*>? = null

                override fun build() = Hikagable {
                    <caret>
                }
            }
            """.trimIndent()
        )
        val initial = PerformerDeclarations.resolve(project)
        assertEquals(listOf("CachedView"), initial.map { declaration -> declaration.functionName })

        myFixture.type("Unit")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val afterExpressionBodyEdit = PerformerDeclarations.resolve(project)

        assertSame(initial, afterExpressionBodyEdit)
    }

    /** Verifies declaration invalidation waits until replacement PSI has reached its stable post-change state. */
    fun testDeclarationSnapshotInvalidatesAfterPsiReplacement() {
        installHikageTestApi()
        enableHikageProject()
        storeGradleModels(owningModel = model())
        addProjectFile(
            "sample/CachedView.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            class CachedView(context: Context, attrs: AttributeSet?) : View(context, attrs)
            """.trimIndent()
        )
        val declarationFile = addProjectFile(
            "sample/CachedViewDeclaration.kt",
            """
            package sample

            import com.highcapable.hikage.annotation.HikageViewDeclaration

            @HikageViewDeclaration(CachedView::class)
            object CachedViewDeclaration
            """.trimIndent()
        )
        val initial = PerformerDeclarations.resolve(project)
        assertEquals(listOf("CachedView"), initial.map { declaration -> declaration.functionName })
        val cache = PerformerDeclarationCache.getInstance(project)
        val initialModificationCount = cache.modificationCount
        val psiManager = PsiManager.getInstance(project) as PsiManagerImpl
        val beforeReplacementEvent = PsiTreeChangeEventImpl(psiManager).apply {
            setFile(declarationFile)
            setOldChild(declarationFile)
            setNewChild(declarationFile)
        }
        WriteCommandAction.runWriteCommandAction(project) {
            psiManager.beforeChildReplacement(beforeReplacementEvent)
            psiManager.afterChange(true)
        }

        assertEquals(initialModificationCount, cache.modificationCount)

        replaceText(
            declarationFile,
            "object CachedViewDeclaration",
            """
            object CachedViewDeclaration {
                val marker = Unit
            }
            """.trimIndent()
        )

        assertTrue(cache.modificationCount > initialModificationCount)
        assertNotSame(initial, PerformerDeclarations.resolve(project))
    }

    /** Verifies new annotation inputs and every referenced Kotlin declaration invalidate the snapshot. */
    fun testDeclarationSnapshotRefreshesForKotlinDeclarationInputs() {
        installHikageTestApi()
        enableHikageProject()
        storeGradleModels(owningModel = model())
        val constantFile = addProjectFile(
            "sample/DeclarationConstants.kt",
            ""
        )
        val baseViewFile = addProjectFile(
            "sample/CachedViewBase.kt",
            ""
        )
        val declarationFile = addProjectFile(
            "sample/CachedViewDeclaration.kt",
            ""
        )
        val referencedViewFile = addProjectFile(
            "sample/CachedView.kt",
            ""
        )
        val introducedViewFile = addProjectFile(
            "sample/IntroducedView.kt",
            ""
        )
        val unrelatedKotlinFile = addProjectFile(
            "sample/UnrelatedKotlinInput.kt",
            ""
        )
        writeText(
            constantFile,
            """
            package sample

            const val VIEW_ALIAS = "Before"
            """.trimIndent()
        )
        writeText(
            baseViewFile,
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.View

            open class CachedViewBase(
                context: Context,
                attrs: AttributeSet?
            ) : View(context, attrs)
            """.trimIndent()
        )
        writeText(
            unrelatedKotlinFile,
            """
            package sample

            class UnrelatedKotlinInput {
                val value = 1
            }
            """.trimIndent()
        )
        writeText(
            referencedViewFile,
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet

            class CachedView(
                context: Context,
                attrs: AttributeSet?
            ) : CachedViewBase(context, attrs)
            """.trimIndent()
        )
        val beforeAnnotationWrite = PerformerDeclarations.resolve(project)
        assertEmpty(beforeAnnotationWrite)

        writeText(
            declarationFile,
            """
            package sample

            import com.highcapable.hikage.annotation.HikageViewDeclaration as PerformerViewDeclaration

            @PerformerViewDeclaration(
                view = CachedView::class,
                alias = VIEW_ALIAS
            )
            object CachedViewDeclaration
            """.trimIndent()
        )
        val afterAnnotationWrite = PerformerDeclarations.resolve(project)

        assertNotSame(beforeAnnotationWrite, afterAnnotationWrite)
        assertEquals(listOf("Before"), afterAnnotationWrite.map { declaration -> declaration.functionName })

        replaceText(unrelatedKotlinFile, "value = 1", "value = 2")
        val afterUnrelatedKotlinEdit = PerformerDeclarations.resolve(project)

        assertSame(afterAnnotationWrite, afterUnrelatedKotlinEdit)

        replaceText(declarationFile, "CachedView::class", "IntroducedView::class")
        val beforeReferencedClassWrite = PerformerDeclarations.resolve(project)

        assertNotSame(afterUnrelatedKotlinEdit, beforeReferencedClassWrite)
        assertEmpty(beforeReferencedClassWrite)

        writeText(
            introducedViewFile,
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet

            class IntroducedView(
                context: Context,
                attrs: AttributeSet?
            ) : CachedViewBase(context, attrs)
            """.trimIndent()
        )
        val afterReferencedClassWrite = PerformerDeclarations.resolve(project)

        assertNotSame(beforeReferencedClassWrite, afterReferencedClassWrite)
        assertEquals(listOf("Before"), afterReferencedClassWrite.map { declaration -> declaration.functionName })

        replaceText(constantFile, "Before", "After")
        val afterConstantEdit = PerformerDeclarations.resolve(project)

        assertNotSame(afterReferencedClassWrite, afterConstantEdit)
        assertEquals(listOf("After"), afterConstantEdit.map { declaration -> declaration.functionName })

        replaceText(introducedViewFile, "AttributeSet?", "AttributeSet")
        val afterConstructorEdit = PerformerDeclarations.resolve(project)

        assertNotSame(afterConstantEdit, afterConstructorEdit)
        assertEmpty(afterConstructorEdit)

        replaceText(introducedViewFile, "attrs: AttributeSet", "attrs: AttributeSet?")
        val afterConstructorRestore = PerformerDeclarations.resolve(project)

        assertNotSame(afterConstructorEdit, afterConstructorRestore)
        assertEquals(listOf("After"), afterConstructorRestore.map { declaration -> declaration.functionName })

        replaceText(baseViewFile, " : View(context, attrs)", "")
        val afterSupertypeEdit = PerformerDeclarations.resolve(project)

        assertNotSame(afterConstructorRestore, afterSupertypeEdit)
        assertEmpty(afterSupertypeEdit)
    }

    /** Verifies only a Java source used by a declaration invalidates the dynamic performer snapshot. */
    fun testDeclarationSnapshotRefreshesForJavaChanges() {
        installHikageTestApi()
        enableHikageProject()
        storeGradleModels(owningModel = model())
        val unrelatedJavaFile = addProjectFile(
            "sample/UnrelatedJavaInput.java",
            """
            package sample;

            public class UnrelatedJavaInput {
                private int value;
            }
            """.trimIndent()
        )
        val trackedJavaFile = addProjectFile(
            "sample/TrackedJavaLayoutParams.java",
            """
            package sample;

            import android.view.ViewGroup;

            public class TrackedJavaLayoutParams extends ViewGroup.LayoutParams {}
            """.trimIndent()
        )
        configureKotlinByText(
            "JavaDeclarationCache.kt",
            """
            package sample

            import android.content.Context
            import android.util.AttributeSet
            import android.view.ViewGroup
            import com.highcapable.hikage.annotation.HikageView

            @HikageView(lparams = TrackedJavaLayoutParams::class)
            class JavaTrackedGroup(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs)
            """.trimIndent()
        )
        val initial = PerformerDeclarations.resolve(project)
        assertEquals(listOf("JavaTrackedGroup"), initial.map { declaration -> declaration.functionName })

        replaceText(unrelatedJavaFile, "int value", "long value")
        val afterUnrelatedJavaEdit = PerformerDeclarations.resolve(project)

        assertSame(initial, afterUnrelatedJavaEdit)

        replaceText(trackedJavaFile, "extends ViewGroup.LayoutParams", "")
        val afterTrackedJavaEdit = PerformerDeclarations.resolve(project)

        assertNotSame(afterUnrelatedJavaEdit, afterTrackedJavaEdit)
        assertEmpty(afterTrackedJavaEdit)
    }

    /** Verifies that only a source module with its own enabled Hikage model receives the extension. */
    fun testResolveExtensionRequiresEnabledOwningModuleModel() {
        val file = configureKotlinByText(
            "ResolveExtensionGate.kt",
            """
            package sample

            class ResolveExtensionGate
            """.trimIndent()
        )
        val sourceModule = KaModuleProvider.getModule(project, file, null)
        val provider = PerformerResolveExtensionProvider()

        assertEmpty(provider.provideExtensionsFor(sourceModule))

        enableHikageProject()
        storeGradleModels(siblingModel = model())
        assertEmpty(provider.provideExtensionsFor(sourceModule))

        storeGradleModels(owningModel = model(), siblingModel = model())
        val extensions = provider.provideExtensionsFor(sourceModule)

        assertEquals(1, extensions.size)
    }

    private fun storeGradleModels(
        owningModel: HikageGradleModel? = null,
        siblingModel: HikageGradleModel? = null
    ) {
        val rootPath = requireNotNull(project.basePath)
        val projectData = ProjectData(GradleConstants.SYSTEM_ID, project.name, rootPath, rootPath)
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val moduleData = ModuleData(
            module.name,
            GradleConstants.SYSTEM_ID,
            "JAVA_MODULE",
            module.name,
            rootPath,
            rootPath
        )
        val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
        owningModel?.let { moduleNode.createChild(HikageGradleToolingModel.key, it) }
        siblingModel?.let { model ->
            projectNode.createChild(
                ProjectKeys.MODULE,
                ModuleData(
                    "com.highcapable.hikage.test.sibling",
                    GradleConstants.SYSTEM_ID,
                    "JAVA_MODULE",
                    "hikage-test-sibling",
                    rootPath,
                    rootPath
                )
            ).createChild(HikageGradleToolingModel.key, model)
        }
        ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(
            GradleConstants.SYSTEM_ID,
            moduleData,
            projectData
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            StoredExternalProjectInfo(GradleConstants.SYSTEM_ID, rootPath, projectNode)
        )
    }

    private fun model() = DefaultHikageGradleModel(
        isPluginApplied = true,
        isCompilerEnabled = true,
        viewDeclarationFiles = emptyList(),
        optionalViewDeclarationFiles = emptyList(),
        strictViewDeclarationInputFiles = emptyList(),
        optionalViewDeclarationInputArtifacts = emptyList()
    )

    private fun replaceText(file: PsiFile, oldText: String, newText: String) {
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        val offset = document.text.indexOf(oldText)
        assertTrue("Expected fixture text '$oldText'", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(offset, offset + oldText.length, newText)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun writeText(file: PsiFile, text: String) {
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private class StoredExternalProjectInfo(
        private val systemId: ProjectSystemId,
        private val path: String,
        private val structure: DataNode<ProjectData>
    ) : ExternalProjectInfo {
        override fun getProjectSystemId() = systemId
        override fun getExternalProjectPath() = path
        override fun getExternalProjectStructure() = structure
        override fun getLastSuccessfulImportTimestamp() = 1L
        override fun getLastImportTimestamp() = 1L
        override fun getBuildNumber() = ""
        override fun copy() = StoredExternalProjectInfo(systemId, path, structure.graphCopy())
    }
}