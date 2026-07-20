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
@file:Suppress("InspectionDescriptionNotFoundInspection")

package com.highcapable.hikage.intellij.mirror.lint

import com.highcapable.hikage.intellij.inspection.base.BaseInspectionTool
import com.highcapable.hikage.intellij.mirror.lint.model.LintIssue
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Mirrors one Android layout Lint issue for statically reconstructed performer calls.
 *
 * The Inspection EP namespaces its profile identity with `AndroidLintMirror` while retaining [issueId] as the
 * upstream suppression ID.
 */
abstract class AndroidLintInspection protected constructor(private val issueId: String) : BaseInspectionTool() {

    final override fun createVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file !is KtFile) return PsiElementVisitor.EMPTY_VISITOR

        return object : KtVisitorVoid() {

            override fun visitKtFile(file: KtFile) {
                AndroidLintMirror.problems(file)
                    .filter { problem -> problem.issue.id == issueId && problem.element.isValid }
                    .forEach { problem ->
                        holder.registerProblem(
                            problem.element,
                            problem.message,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        )
                    }
            }
        }
    }

    /**
     * Reports the official Android Lint `ContentDescription` issue in reconstructed layouts.
     */
    class ContentDescriptionInspection : AndroidLintInspection(LintIssue.CONTENT_DESCRIPTION.id)

    /**
     * Reports the official Android Lint `KeyboardInaccessibleWidget` issue in reconstructed layouts.
     */
    class KeyboardInaccessibleWidgetInspection : AndroidLintInspection(LintIssue.KEYBOARD_INACCESSIBLE_WIDGET.id)

    /**
     * Reports the official Android Lint `LabelFor` issue in reconstructed layouts.
     */
    class LabelForInspection : AndroidLintInspection(LintIssue.LABEL_FOR.id)

    /**
     * Reports the official Android Lint `HardcodedText` issue in reconstructed layouts.
     */
    class HardcodedTextInspection : AndroidLintInspection(LintIssue.HARDCODED_TEXT.id)

    /**
     * Reports the official Android Lint `RelativeOverlap` issue in reconstructed layouts.
     */
    class RelativeOverlapInspection : AndroidLintInspection(LintIssue.RELATIVE_OVERLAP.id)

    /**
     * Reports the official Android Lint `RtlHardcoded` issue in reconstructed layouts.
     */
    class RtlHardcodedInspection : AndroidLintInspection(LintIssue.RTL_HARDCODED.id)

    /**
     * Reports the official Android Lint `AdapterViewChildren` issue in reconstructed layouts.
     */
    class AdapterViewChildrenInspection : AndroidLintInspection(LintIssue.ADAPTER_VIEW_CHILDREN.id)

    /**
     * Reports the official Android Lint `ScrollViewCount` issue in reconstructed layouts.
     */
    class ScrollViewCountInspection : AndroidLintInspection(LintIssue.SCROLL_VIEW_COUNT.id)

    /**
     * Reports the official Android Lint `BottomAppBar` issue in reconstructed layouts.
     */
    class BottomAppBarInspection : AndroidLintInspection(LintIssue.BOTTOM_APP_BAR.id)

    /**
     * Reports the official Android Lint `NestedScrolling` issue in reconstructed layouts.
     */
    class NestedScrollingInspection : AndroidLintInspection(LintIssue.NESTED_SCROLLING.id)

    /**
     * Reports the official Android Lint `TooDeepLayout` issue in reconstructed layouts.
     */
    class TooDeepLayoutInspection : AndroidLintInspection(LintIssue.TOO_DEEP_LAYOUT.id)

    /**
     * Reports the official Android Lint `TooManyViews` issue in reconstructed layouts.
     */
    class TooManyViewsInspection : AndroidLintInspection(LintIssue.TOO_MANY_VIEWS.id)

    /**
     * Reports the official Android Lint `RtlCompat` issue in reconstructed layouts.
     */
    class RtlCompatInspection : AndroidLintInspection(LintIssue.RTL_COMPAT.id)

    /**
     * Reports the official Android Lint `RtlSymmetry` issue in reconstructed layouts.
     */
    class RtlSymmetryInspection : AndroidLintInspection(LintIssue.RTL_SYMMETRY.id)

    /**
     * Reports the official Android Lint `EllipsizeMaxLines` issue in reconstructed layouts.
     */
    class EllipsizeMaxLinesInspection : AndroidLintInspection(LintIssue.ELLIPSIZE_MAX_LINES.id)

    /**
     * Reports the official Android Lint `InvalidImeActionId` issue in reconstructed layouts.
     */
    class InvalidImeActionIdInspection : AndroidLintInspection(LintIssue.INVALID_IME_ACTION_ID.id)

    /**
     * Reports the official Android Lint `Autofill` issue in reconstructed layouts.
     */
    class AutofillInspection : AndroidLintInspection(LintIssue.AUTOFILL.id)

    /**
     * Reports the official Android Lint `TextFields` issue in reconstructed layouts.
     */
    class TextFieldsInspection : AndroidLintInspection(LintIssue.TEXT_FIELDS.id)

    /**
     * Reports the official Android Lint `TextViewEdits` issue in reconstructed layouts.
     */
    class TextViewEditsInspection : AndroidLintInspection(LintIssue.TEXT_VIEW_EDITS.id)

    /**
     * Reports the official Android Lint `PxUsage` issue in reconstructed layouts.
     */
    class PxUsageInspection : AndroidLintInspection(LintIssue.PX_USAGE.id)

    /**
     * Reports the official Android Lint `SpUsage` issue in reconstructed layouts.
     */
    class SpUsageInspection : AndroidLintInspection(LintIssue.SP_USAGE.id)

    /**
     * Reports the official Android Lint `InOrMmUsage` issue in reconstructed layouts.
     */
    class InOrMmUsageInspection : AndroidLintInspection(LintIssue.IN_OR_MM_USAGE.id)

    /**
     * Reports the official Android Lint `SmallSp` issue in reconstructed layouts.
     */
    class SmallSpInspection : AndroidLintInspection(LintIssue.SMALL_SP.id)
}