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

import com.highcapable.hikage.analysis.layout.HikageLayoutResolver
import com.highcapable.hikage.project.ProjectGate
import com.intellij.icons.AllIcons
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import javax.swing.Icon

/**
 * Presents resolved Hikage Layout ID lookup strings as reference targets.
 */
class HikageLayoutIdIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val expression = element as? KtStringTemplateExpression ?: return null
        if (!ProjectGate.from(expression.project).isEnabled()) return null
        if (HikageLayoutResolver.from(expression.project).resolveIdLookup(expression) == null) return null

        return AllIcons.Nodes.Property
    }
}