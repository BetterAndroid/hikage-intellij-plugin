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
 * This file is created by fankes on 2026/7/23.
 */
package com.highcapable.hikage.reference

import com.intellij.icons.AllIcons
import com.intellij.ide.presentation.Presentation
import com.intellij.openapi.application.ReadAction
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.FakePsiElement
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Represents a Hikage performer in reverse Layout ID Find Usages while navigating to its resolved View.
 *
 * Find Usages recreates every primary PSI target as a plain `PsiElement2UsageTargetAdapter`. A custom adapter is therefore
 * discarded before the results window is created. This lightweight PSI element survives that conversion and prevents the
 * adapter from asking Kotlin's generated-source filter to resolve an in-memory performer stub on the EDT.
 */
@Presentation(typeName = "Hikage Performer Usage Target Element")
class PerformerUsageTargetElement(searchTarget: PsiElement, navigationTarget: PsiElement?) : FakePsiElement() {

    private val targetProject = searchTarget.project
    private val targetName = (searchTarget as? PsiNamedElement)?.name ?: searchTarget.text
    private val targetLocation = navigationTarget?.containingFile?.virtualFile?.presentableUrl
    private val searchTargetPointer = SmartPointerManager.getInstance(targetProject).createSmartPsiElementPointer(searchTarget)
    private val navigationTargetPointer = navigationTarget?.let { element ->
        SmartPointerManager.getInstance(targetProject).createSmartPsiElementPointer(element)
    }

    val searchTarget get() = searchTargetElement()
    val performer get() = searchTarget as? KtExpression

    override fun getParent() = searchTarget
    override fun getProject() = targetProject
    override fun getContainingFile() = searchTarget?.containingFile
    override fun getName(): String? = targetName
    override fun getPresentableText(): String? = targetName
    override fun getLocationString() = targetLocation
    override fun getIcon(unused: Boolean) = AllIcons.Nodes.Function
    override fun getNavigationElement() = navigationTargetElement() ?: this
    override fun isValid() = searchTarget != null
    override fun isWritable() = false

    override fun navigate(requestFocus: Boolean) {
        val target = navigationTarget() ?: return
        if (target.canNavigate()) target.navigate(requestFocus)
    }

    override fun canNavigate() = navigationTarget()?.canNavigate() == true

    override fun canNavigateToSource() = navigationTarget()?.canNavigateToSource() == true

    /** Find Usages dereferences its primary target on a pooled thread without an enclosing read action. */
    private fun searchTargetElement() = ReadAction.computeCancellable<PsiElement?, RuntimeException> {
        searchTargetPointer.element
    }

    private fun navigationTargetElement() = ReadAction.computeCancellable<PsiElement?, RuntimeException> {
        navigationTargetPointer?.element
    }

    private fun navigationTarget() = navigationTargetElement() as? Navigatable
}