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
 * This file is created by fankes on 2026/7/7.
 */
package com.highcapable.hikage.completion

import com.highcapable.hikage.project.ProjectGate
import com.intellij.codeInsight.completion.impl.TopPriorityLookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.impl.LookupCustomizer
import com.intellij.codeInsight.lookup.impl.LookupImpl

/**
 * Keeps `Hikagable` functions selected when platform completion tries to preserve a same-named class selection.
 */
class HikagableLookupCustomizer : LookupCustomizer {

    override fun customizeLookup(lookupImpl: LookupImpl) {
        if (!ProjectGate.from(lookupImpl.project).isEnabled()) return
        lookupImpl.addLookupListener(LookupSelectionListener(lookupImpl))
    }

    private class LookupSelectionListener(private val lookup: LookupImpl) : LookupListener {

        private var isAdjustingSelection = false

        override fun uiRefreshed() {
            adjustSelection()
        }

        override fun currentItemChanged(event: LookupEvent) {
            adjustSelection()
        }

        private fun adjustSelection() {
            if (isAdjustingSelection || lookup.isLookupDisposed) return

            val currentItem = lookup.currentItem ?: return
            if (currentItem.getUserData(HikagableCompletionContributor.classifierLookupKey) != true) return

            // Sorting and preselection are separate paths in lookup UI. Even after the contributor
            // places the Hikage function above the class, the platform can preserve the old selected
            // classifier by presentation, which makes Enter insert the class import.
            // Correct only that narrow case: the selected row is a K2 classifier and a same-named,
            // top-priority receiver function is already present.
            val targetIndex = lookup.items.indexOfFirst { lookupElement ->
                lookupElement.lookupString == currentItem.lookupString &&
                    TopPriorityLookupElement.isTopPriorityItem(lookupElement) &&
                    lookupElement.getUserData(HikagableCompletionContributor.receiverFunctionLookupKey) == true
            }
            if (targetIndex < 0 || targetIndex == lookup.selectedIndex) return
            if (lookup.isSelectionTouched) return

            isAdjustingSelection = true
            try {
                // Assign selectedIndex directly. setCurrentItem() marks the lookup selection as
                // touched, which would make later refreshes look like explicit user navigation and
                // reintroduce the sticky class preselection this listener is fixing.
                lookup.selectedIndex = targetIndex
            } finally {
                isAdjustingSelection = false
            }
        }
    }
}