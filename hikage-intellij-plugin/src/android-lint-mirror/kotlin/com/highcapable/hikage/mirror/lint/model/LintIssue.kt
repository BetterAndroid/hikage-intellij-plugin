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
package com.highcapable.hikage.mirror.lint.model

/**
 * Lists the host Android Lint issues mirrored into Kotlin inspections.
 * @param id the upstream Android Lint issue identifier.
 */
enum class LintIssue(val id: String) {
    CONTENT_DESCRIPTION("ContentDescription"),
    KEYBOARD_INACCESSIBLE_WIDGET("KeyboardInaccessibleWidget"),
    LABEL_FOR("LabelFor"),
    HARDCODED_TEXT("HardcodedText"),
    RELATIVE_OVERLAP("RelativeOverlap"),
    RTL_HARDCODED("RtlHardcoded"),
    ADAPTER_VIEW_CHILDREN("AdapterViewChildren"),
    SCROLL_VIEW_COUNT("ScrollViewCount"),
    BOTTOM_APP_BAR("BottomAppBar"),
    NESTED_SCROLLING("NestedScrolling"),
    TOO_DEEP_LAYOUT("TooDeepLayout"),
    TOO_MANY_VIEWS("TooManyViews"),
    RTL_COMPAT("RtlCompat"),
    RTL_SYMMETRY("RtlSymmetry"),
    ELLIPSIZE_MAX_LINES("EllipsizeMaxLines"),
    INVALID_IME_ACTION_ID("InvalidImeActionId"),
    AUTOFILL("Autofill"),
    TEXT_FIELDS("TextFields"),
    TEXT_VIEW_EDITS("TextViewEdits"),
    PX_USAGE("PxUsage"),
    SP_USAGE("SpUsage"),
    IN_OR_MM_USAGE("InOrMmUsage"),
    SMALL_SP("SmallSp")
}