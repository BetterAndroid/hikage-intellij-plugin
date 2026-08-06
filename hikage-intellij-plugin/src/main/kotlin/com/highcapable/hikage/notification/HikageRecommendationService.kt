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
package com.highcapable.hikage.notification

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.highcapable.hikage.generated.PluginProperties
import com.highcapable.hikage.notification.bundle.NotificationBundle
import com.highcapable.hikage.project.Coordinates
import com.highcapable.hikage.project.GradleDependencyService
import com.highcapable.hikage.project.ProjectGate
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Recommends Hikage after project startup or Gradle import when an eligible Android application has no Hikage dependency.
 */
@Service(Service.Level.PROJECT)
class HikageRecommendationService(private val project: Project) : Disposable {

    companion object {

        private const val NOTIFICATION_GROUP_ID = "Hikage Recommendations"
        private const val RECOMMENDATION_DISPLAY_ID = "${PluginProperties.PROJECT_PLUGIN_ID}.recommendation.setup"
        private const val FAILURE_DISPLAY_ID = "${PluginProperties.PROJECT_PLUGIN_ID}.recommendation.setup.failure"
        private const val LAYOUT_DIRECTORY_NAME = "layout"
        private const val XML_EXTENSION = "xml"

        /**
         * Returns the recommendation service for [project].
         * @return [HikageRecommendationService]
         */
        fun getInstance(project: Project) = project.service<HikageRecommendationService>()
    }

    private var hasRecommended = false

    init {
        project.messageBus.connect(this).subscribe(ProjectDataImportListener.TOPIC, object : ProjectDataImportListener {
            override fun onImportFinished(projectPath: String?) = scheduleRecommendationCheck()
        })
    }

    /** Checks the current project once and keeps checking after later Gradle imports. */
    fun start() = scheduleRecommendationCheck()

    private fun scheduleRecommendationCheck() {
        ReadAction.nonBlocking<Module?> { project.findRecommendationModule() }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { module ->
                if (module == null || module.isDisposed || hasRecommended) return@finishOnUiThread

                hasRecommended = true
                showRecommendation(module)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun Project.findRecommendationModule(): Module? {
        if (isDisposed || !isOpen) return null

        val module = ModuleManager.getInstance(this).modules.firstOrNull { module -> module.hasXmlLayout() } ?: return null
        if (GradleDependencyService.getInstance(this).hasAnyDependency(Coordinates.GROUP)) return null
        return module
    }

    private fun Module.hasXmlLayout(): Boolean {
        val model = GradleAndroidModel.get(this) ?: return false
        return model.androidProject.projectType == IdeAndroidProjectType.PROJECT_TYPE_APP && model.activeSourceProviders.asSequence()
            .flatMap { sourceProvider -> sourceProvider.resDirectories.asSequence() }
            .map { resourceDirectory -> resourceDirectory.resolve(LAYOUT_DIRECTORY_NAME) }
            .any { layoutDirectory ->
                layoutDirectory.listFiles()?.any { file ->
                    file.isFile && file.extension.equals(XML_EXTENSION, ignoreCase = true)
                } == true
            }
    }

    private fun showRecommendation(module: Module) {
        Notification(
            NOTIFICATION_GROUP_ID,
            NotificationBundle.message("notification.recommendation.title"),
            NotificationBundle.message("notification.recommendation.content"),
            NotificationType.INFORMATION
        ).setDisplayId(RECOMMENDATION_DISPLAY_ID)
            .setSuggestionType(true)
            .addAction(NotificationAction.create(NotificationBundle.message("notification.recommendation.action")) { _, notification ->
                if (ProjectGate.from(project).addHikageDependencies(module)) notification.expire() else showFailure()
            })
            .notify(project)
    }

    private fun showFailure() {
        Notification(
            NOTIFICATION_GROUP_ID,
            NotificationBundle.message("notification.recommendation.failure.title"),
            NotificationBundle.message("notification.recommendation.failure.content"),
            NotificationType.WARNING
        ).setDisplayId(FAILURE_DISPLAY_ID)
            .notify(project)
    }

    override fun dispose() = Unit
}