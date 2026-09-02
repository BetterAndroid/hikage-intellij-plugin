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
package com.highcapable.hikage.refactoring

import com.highcapable.hikage.refactoring.attribute.HikageAttributeRenameHandler
import com.highcapable.hikage.refactoring.layout.HikageLayoutIdRenameHandler
import com.highcapable.hikage.refactoring.layout.HikageLayoutIdRenameProcessor
import com.highcapable.hikage.refactoring.layout.HikageLayoutIdRenameTargetResolver
import com.highcapable.hikage.test.framework.HikageCodeInsightTestCase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.kotlin.analysis.api.permissions.forbidAnalysis

/**
 * Verifies the shared Rename action dispatch and Layout ID refactoring isolation.
 */
class HikageRenameRegressionTest : HikageCodeInsightTestCase() {

    /** Verifies shared Rename action availability never starts Kotlin Analysis while the editor is changing. */
    fun testSharedActionAvailabilityIsAnalysisFree() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "HikageRenameAvailability.kt",
            """
            package com.highcapable.hikage.fixture.refactoring

            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.HikageAttribute
            import com.highcapable.hikage.core.attribute.set
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.TextView(id: String = ""): TextView = error("Test stub")

            val layout = Hikagable {
                TextView(id = "title")
            }
            val lookup = layout["title"]

            val attrs = HikageAttribute {
                set("android:text", "Hello")
            }
            """.trimIndent()
        )
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val action = HikageRenameAction()

        listOf(
            file.text.lastIndexOf("\"title\"") + 2,
            file.text.indexOf("\"android:text\"") + 2
        ).forEach { offset ->
            myFixture.editor.caretModel.moveToOffset(offset)
            val event = TestActionEvent.createTestEvent(action, dataContext)
            forbidAnalysis("Hikage Rename action availability") {
                ActionUtil.updateAction(action, event)
            }
            assertTrue(event.presentation.isEnabled)
        }
    }

    /** Verifies both supported string domains remain reachable through the single shared action. */
    fun testSharedActionRecognizesLayoutAndAttributeRenameContexts() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "HikageRenameDispatch.kt",
            """
            package sample

            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.attribute.HikageAttribute
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.TextView(id: String = ""): TextView = error("Test stub")

            val layout = Hikagable {
                TextView(id = "title")
            }
            val lookup = layout["title"]

            val attrs = HikageAttribute {
                set("android:text", "Hello")
            }
            """.trimIndent()
        )
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()

        myFixture.editor.caretModel.moveToOffset(file.text.lastIndexOf("\"title\"") + 2)
        assertTrue(HikageLayoutIdRenameHandler().isAvailableOnDataContext(dataContext))

        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("\"android:text\"") + 2)
        assertTrue(HikageAttributeRenameHandler().isAvailableOnDataContext(dataContext))
        assertTrue(ActionManager.getInstance().getAction("com.highcapable.hikage.rename") is HikageRenameAction)
    }

    /** Verifies Rename availability ignores ordinary `set`, map, object, and `id`-parameter strings. */
    fun testRenameAvailabilityIgnoresOrdinaryStringContexts() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        enableHikageRuntimeAttribute()
        val file = configureKotlinByText(
            "OrdinaryRenameContexts.kt",
            """
            package sample

            class Holder {
                fun get(id: String) = id
            }

            fun set(value: String) = Unit
            fun ordinary(id: String) = id

            val values = mapOf("title" to 1)
            val holder = Holder()
            val ordinarySet = set("ordinary")
            val ordinaryId = ordinary(id = "title")
            val ordinaryMap = values["title"]
            val ordinaryObject = holder.get("title")
            """.trimIndent()
        )
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val layoutHandler = HikageLayoutIdRenameHandler()
        val attributeHandler = HikageAttributeRenameHandler()

        fun assertUnavailable(offset: Int, isAvailable: () -> Boolean) {
            myFixture.editor.caretModel.moveToOffset(offset)
            forbidAnalysis("ordinary Rename availability") {
                assertFalse(isAvailable())
            }
        }

        val ordinarySetOffset = file.text.indexOf("\"ordinary\"") + 2
        assertUnavailable(ordinarySetOffset) { layoutHandler.isAvailableOnDataContext(dataContext) }
        assertUnavailable(ordinarySetOffset) { attributeHandler.isAvailableOnDataContext(dataContext) }
        val ordinaryMapOffset = file.text.indexOf("ordinaryMap")
            .let { start -> file.text.indexOf("\"title\"", start) + 2 }
        assertUnavailable(ordinaryMapOffset) { layoutHandler.isAvailableOnDataContext(dataContext) }
        val ordinaryObjectOffset = file.text.indexOf("ordinaryObject")
            .let { start -> file.text.indexOf("\"title\"", start) + 2 }
        assertUnavailable(ordinaryObjectOffset) { layoutHandler.isAvailableOnDataContext(dataContext) }
    }

    /** Verifies Rename from one lookup updates only IDs owned by the same layout source. */
    fun testLayoutLookupRenameUpdatesItsDeclarationAndResolvedUsagesOnly() {
        installHikageTestApi()
        installAndroidWidgetTestApi()
        enableHikageProject()
        val file = configureKotlinByText(
            "HikageLayoutIdRename.kt",
            """
            package sample

            import android.widget.TextView
            import com.highcapable.hikage.annotation.Hikagable
            import com.highcapable.hikage.core.Hikage
            import com.highcapable.hikage.core.base.Hikagable

            @Hikagable
            fun Hikage.Performer.TextView(id: String = ""): TextView = error("Test stub")

            val first = Hikagable {
                TextView(id = "title")
            }
            val second = Hikagable {
                TextView(id = "title")
            }

            val firstArray = first["ti<caret>tle"]
            val firstGet = first.get("title")
            val secondArray = second["title"]
            """.trimIndent()
        )
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val target = requireNotNull(HikageLayoutIdRenameTargetResolver.findTarget(dataContext))
        val processor = HikageLayoutIdRenameProcessor()
        val references = processor.findReferences(target, target.useScope, false)

        WriteCommandAction.runWriteCommandAction(project) {
            references.forEach { reference -> reference.handleElementRename("renamed") }
            target.setName("renamed")
        }

        val source = myFixture.file.text
        assertContains(source, "first[\"renamed\"]")
        assertContains(source, "first.get(\"renamed\")")
        assertContains(source, "second[\"title\"]")
        assertEquals(3, "\"renamed\"".toRegex().findAll(source).count())
        assertEquals(2, "\"title\"".toRegex().findAll(source).count())
    }
}