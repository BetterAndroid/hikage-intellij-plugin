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
 * This file is created by fankes on 2026/7/17.
 */
package com.highcapable.hikage.project

import com.android.ide.common.gradle.Dependency
import com.android.ide.common.gradle.RichVersion
import com.android.tools.idea.gradle.dependencies.CatalogDependenciesInserter
import com.android.tools.idea.gradle.dependencies.DependenciesConfig
import com.android.tools.idea.gradle.dependencies.DependenciesHelper
import com.android.tools.idea.gradle.dependencies.DependenciesProcessor
import com.android.tools.idea.gradle.dependencies.DependencyDescription
import com.android.tools.idea.gradle.dependencies.GradleDependencyManager
import com.android.tools.idea.gradle.dependencies.PlatformDescription
import com.android.tools.idea.gradle.dependencies.PluginDescription
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo
import com.android.tools.idea.gradle.repositories.RepositoryUrlManager
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager
import com.android.tools.idea.projectsystem.getSyncManager
import com.highcapable.hikage.utils.extension.failOpen
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * Provides source-aware Gradle dependency detection and modification for the current project.
 */
@Service(Service.Level.PROJECT)
class GradleDependencyService(private val project: Project) {

    companion object {

        private const val DEFAULT_CONFIGURATION = "implementation"

        /**
         * Returns the Gradle dependency service for [project].
         * @return [GradleDependencyService]
         */
        fun getInstance(project: Project) = project.service<GradleDependencyService>()
    }

    /**
     * Returns whether [coordinate] is directly declared for [module] or supplies [capabilityClassName].
     * @param module the target module to check.
     * @param coordinate the Gradle dependency coordinate to check.
     * @param capabilityClassName the fully qualified class name to check for existence in [module].
     * @return [Boolean]
     */
    fun isDependencyApplied(module: Module, coordinate: String, capabilityClassName: String? = null) = failOpen {
        val target = Dependency.parse(coordinate)
        if (target.group == null) return@failOpen true
        val buildModel = ProjectBuildModel.get(project).getModuleBuildModel(module) ?: return@failOpen true
        val isDeclared = buildModel.hasDependency(target)
        if (isDeclared || capabilityClassName == null) return@failOpen isDeclared

        JavaPsiFacade.getInstance(project).findClass(
            capabilityClassName,
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        ) != null
    } ?: true

    /**
     * Returns whether any module directly or transitively uses a dependency from [group].
     * @param group the Maven group to search for.
     * @return [Boolean]
     */
    fun hasAnyDependency(group: String) = failOpen {
        val hasDeclaredDependency = ProjectBuildModel.get(project).allIncludedBuildModels.any { buildModel ->
            buildModel.dependencies().artifacts().any { dependency -> dependency.spec.group == group }
        }
        hasDeclaredDependency || ModuleManager.getInstance(project).modules.any { module ->
            ModuleRootManager.getInstance(module).orderEntries
                .asSequence()
                .filterIsInstance<LibraryOrderEntry>()
                .mapNotNull(LibraryOrderEntry::getLibrary)
                .mapNotNull(JavaLibraryUtil::getMavenCoordinates)
                .any { coordinates -> coordinates.groupId == group }
        }
    } ?: true

    /**
     * Adds [coordinate] to [module] and requests Gradle sync after a successful modification.
     * @param module the target module to add the dependency to.
     * @param coordinate the Gradle dependency coordinate to add.
     * @param configuration the Gradle dependency configuration to add to, default is `implementation`.
     * @param platformCoordinate an optional versioned platform coordinate that should manage the dependency.
     * @return [Boolean] whether the dependency was successfully added.
     */
    fun addDependency(
        module: Module,
        coordinate: String,
        configuration: String = DEFAULT_CONFIGURATION,
        platformCoordinate: String? = null
    ): Boolean {
        val dependency = Dependency.parse(coordinate)
        if (dependency.group == null || module.isDisposed) return false

        val projectModel = ProjectBuildModel.get(project)
        val buildModel = projectModel.getModuleBuildModel(module) ?: return false
        val platform = platformCoordinate?.let(Dependency::parse)
        if (platform != null && platform.group == null) return false

        val isAdded = when {
            platform != null && buildModel.hasDependency(platform) ->
                addPlatformManagedDependency(projectModel, buildModel, dependency, coordinate, configuration)
            platform != null -> addPlatformAndManagedDependency(
                projectModel = projectModel,
                module = module,
                platformCoordinate = platform.resolveLatestOrDeclared().toIdentifier() ?: return false,
                coordinate = coordinate,
                configuration = configuration
            )
            else -> GradleDependencyManager.getInstance(project)
                .addDependencies(module, listOf(dependency.resolveLatestOrDeclared()), configuration)
        }

        if (isAdded) requestSync()
        return isAdded
    }

    /**
     * Adds one version platform, its managed [coordinates], and a Gradle plugin to [module] in one model update.
     * @param module the target module to configure.
     * @param platformCoordinate the versioned platform coordinate declared by this plugin build.
     * @param coordinates the versionless dependencies managed by the platform.
     * @param pluginId the Gradle plugin ID to apply.
     * @param pluginVersion the Gradle plugin version declared by this plugin build.
     * @param pluginClasspathCoordinate the plugin implementation coordinate used by legacy Gradle builds.
     * @param pluginAlias the preferred Version Catalog plugin alias.
     * @param pluginVersionAlias the preferred Version Catalog version alias.
     * @param configuration the dependency configuration to add to, default is `implementation`.
     * @return [Boolean] whether at least one Gradle file was updated.
     */
    fun addDependenciesAndPlugin(
        module: Module,
        platformCoordinate: String,
        coordinates: List<String>,
        pluginId: String,
        pluginVersion: String,
        pluginClasspathCoordinate: String,
        pluginAlias: String,
        pluginVersionAlias: String,
        configuration: String = DEFAULT_CONFIGURATION
    ): Boolean {
        val platform = Dependency.parse(platformCoordinate)
        val plugin = Dependency.parse("$pluginClasspathCoordinate:$pluginVersion")
        if (coordinates.isEmpty() || module.isDisposed || platform.group == null ||
            platform.explicitSingletonVersion == null || plugin.group == null
        ) return false

        val resolvedPlatformCoordinate = platform.resolveLatestOrDeclared().toIdentifier() ?: return false
        val resolvedPluginVersion = plugin.resolveLatestOrDeclared().explicitSingletonVersion?.toString() ?: return false
        val isAdded = applyDependenciesAndPlugin(
            module = module,
            platformCoordinate = resolvedPlatformCoordinate,
            coordinates = coordinates,
            pluginId = pluginId,
            pluginVersion = resolvedPluginVersion,
            pluginClasspathCoordinate = pluginClasspathCoordinate,
            pluginAlias = pluginAlias,
            pluginVersionAlias = pluginVersionAlias,
            configuration = configuration
        )

        if (isAdded) requestSync()
        return isAdded
    }

    private fun applyDependenciesAndPlugin(
        module: Module,
        platformCoordinate: String,
        coordinates: List<String>,
        pluginId: String,
        pluginVersion: String,
        pluginClasspathCoordinate: String,
        pluginAlias: String,
        pluginVersionAlias: String,
        configuration: String
    ): Boolean {
        val projectModel = ProjectBuildModel.get(project)
        projectModel.getModuleBuildModel(module) ?: return false

        val config = DependenciesConfig.defaultConfig()
            .withPlatform(PlatformDescription(configuration, platformCoordinate, false))
            .withDependencies(coordinates.map { coordinate -> DependencyDescription(configuration, coordinate) })
            .withPlugin(PluginDescription(pluginId, pluginVersion, pluginClasspathCoordinate))
        val isAdded = WriteCommandAction.writeCommandAction(project)
            .withName("Add Hikage")
            .compute<Boolean, RuntimeException> {
                preparePluginCatalogAlias(
                    projectModel = projectModel,
                    pluginId = pluginId,
                    pluginVersion = pluginVersion,
                    pluginAlias = pluginAlias,
                    pluginVersionAlias = pluginVersionAlias
                )
                val result = DependenciesProcessor(projectModel).apply(config, module)
                if (!result.success || result.updated.isEmpty()) return@compute false

                projectModel.applyChanges()
                true
            }
        return isAdded
    }

    private fun addPlatformAndManagedDependency(
        projectModel: ProjectBuildModel,
        module: Module,
        platformCoordinate: String,
        coordinate: String,
        configuration: String
    ): Boolean {
        val config = DependenciesConfig.defaultConfig()
            .withPlatform(PlatformDescription(configuration, platformCoordinate, false))
            .withDependencies(listOf(DependencyDescription(configuration, coordinate)))
        return WriteCommandAction.writeCommandAction(project)
            .withName("Add Dependency")
            .compute<Boolean, RuntimeException> {
                val result = DependenciesProcessor(projectModel).apply(config, module)
                if (!result.success || result.updated.isEmpty()) return@compute false

                projectModel.applyChanges()
                true
            }
    }

    private fun requestSync() = project.getSyncManager().requestSyncProject(ProjectSystemSyncManager.SyncReason.PROJECT_DEPENDENCY_UPDATED)

    private fun Dependency.resolveLatestOrDeclared(): Dependency {
        if (explicitSingletonVersion == null) return this

        // RepositoryUrlManager returns singleton versions without repository lookup. Remove only the declared version
        // for native discovery, then retain this dependency unchanged when no repository or cache result is available.
        val resolved = RepositoryUrlManager.get().resolveDependency(copy(version = null), project, null) ?: return this
        return copy(version = RichVersion.require(resolved.version))
    }

    private fun preparePluginCatalogAlias(
        projectModel: ProjectBuildModel,
        pluginId: String,
        pluginVersion: String,
        pluginAlias: String,
        pluginVersionAlias: String
    ) {
        val catalog = DependenciesHelper.getDefaultCatalogModel(projectModel) ?: return
        val plugins = catalog.pluginDeclarations()
        if (plugins.getAll().values.any { plugin -> plugin.id().valueAsString() == pluginId }) return

        // DependenciesProcessor cannot accept a preferred alias. Pre-declaring it through the same Gradle DSL model
        // lets the official plugin inserter reuse the Hikage-owned names instead of deriving them from the group ID.
        val versions = catalog.versionDeclarations()
        val versionAlias = pluginVersionAlias.findAvailableCatalogAlias(versions.getAllAliases())
        val version = versions.addDeclaration(versionAlias, pluginVersion) ?: return
        val alias = pluginAlias.findAvailableCatalogAlias(plugins.getAllAliases())
        plugins.addDeclaration(alias, pluginId, ReferenceTo(version, plugins))
    }

    private fun String.findAvailableCatalogAlias(existing: Set<String>) = if (this in existing)
        generateSequence(2) { index -> index + 1 }
            .map { index -> "$this-$index" }
            .first { alias -> alias !in existing }
    else this

    /**
     * Android Studio resolves a versionless coordinate before its normal insertion policy runs. That would detach a
     * library from an already applied BOM, so this path writes the versionless declaration through the Gradle DSL model.
     * @param projectModel the Gradle project model.
     * @param buildModel the Gradle build model for the target module.
     * @param dependency the Gradle dependency to add.
     * @param coordinate the Gradle dependency coordinate to add.
     * @param configuration the Gradle dependency configuration to add to.
     * @return [Boolean] whether the dependency was successfully added.
     */
    private fun addPlatformManagedDependency(
        projectModel: ProjectBuildModel,
        buildModel: GradleBuildModel,
        dependency: Dependency,
        coordinate: String,
        configuration: String
    ) = WriteCommandAction.writeCommandAction(project).withName("Add Dependency").compute<Boolean, RuntimeException> {
        if (buildModel.hasDependency(dependency)) return@compute false

        val dependencies = buildModel.dependencies()
        val catalog = DependenciesHelper.getDefaultCatalogModel(projectModel)
        if (catalog == null)
            dependencies.addArtifact(configuration, coordinate)
        else {
            val alias = catalog.libraryDeclarations().getAll().entries.firstOrNull { (_, declaration) ->
                val spec = declaration.getSpec()
                spec.getGroup() == dependency.group && spec.getName() == dependency.name &&
                    spec.getVersion()?.compactNotation().isNullOrBlank()
            }?.key ?: CatalogDependenciesInserter.addCatalogLibrary(catalog, coordinate) ?: return@compute false
            val declaration = catalog.libraries().findProperty(alias)
            dependencies.addArtifact(configuration, ReferenceTo(declaration, dependencies))
        }

        projectModel.applyChanges()
        true
    }

    private fun GradleBuildModel.hasDependency(target: Dependency): Boolean {
        val group = target.group ?: return false
        return dependencies().artifacts().any { dependency ->
            dependency.spec.group == group && dependency.spec.name == target.name
        }
    }
}