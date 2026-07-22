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
 * This file is created by fankes on 2026/7/19.
 */
@file:Suppress("SameParameterValue")

package com.highcapable.hikage.project.model.android

import com.android.AndroidXConstants
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.resources.ResourceRepository
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.resources.ResourceVisibility
import com.android.tools.dom.attrs.AttributeDefinition
import com.android.tools.dom.attrs.AttributeDefinitions
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.res.getResourceItems
import com.android.tools.idea.res.isAccessible
import com.highcapable.hikage.generated.PluginProperties
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.android.dom.AndroidDomUtil
import org.jetbrains.android.dom.AttributeProcessingUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.facet.findViewValidInXMLByName
import org.jetbrains.android.resourceManagers.ModuleResourceManagers
import java.util.concurrent.CancellationException

/**
 * Adapts Android Studio's XML attribute and resource models for IDE feature consumers.
 */
class AndroidAttributeResolver private constructor(private val facet: AndroidFacet) {

    companion object {

        private const val ANDROID_NAMESPACE = "android"
        private const val APP_NAMESPACE = "app"
        private const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
        private const val SCROLL_VIEW_NAME = "ScrollView"

        private val RESOLVER_KEY = Key.create<CachedValue<AndroidAttributeResolver>>(
            "${PluginProperties.PROJECT_PLUGIN_ID}.androidAttributeResolver"
        )

        /**
         * Creates a resolver for the Android module containing [element].
         * @param element the PSI element to resolve attributes for.
         * @return [AndroidAttributeResolver] or null.
         */
        fun from(element: PsiElement): AndroidAttributeResolver? {
            val facet = AndroidFacet.getInstance(element) ?: return null
            val project = element.project

            return failOpen {
                CachedValuesManager.getManager(project).getCachedValue(
                    facet,
                    RESOLVER_KEY,
                    {
                        val resolver = AndroidAttributeResolver(facet)
                        CachedValueProvider.Result.create(
                            resolver,
                            ProjectRootModificationTracker.getInstance(project),
                            DumbService.getInstance(project).modificationTracker,
                            ModificationTracker { resolver.appResources.modificationCount }
                        )
                    },
                    false
                )
            }
        }

        private inline fun <T> failOpen(action: () -> T): T? = try {
            action()
        } catch (error: Exception) {
            if (error is ControlFlowException || error is CancellationException) throw error
            null
        }
    }

    private val moduleResourceManagers = ModuleResourceManagers.getInstance(facet)
    private val repositoryManager = StudioResourceRepositoryManager.getInstance(facet)
    private val appResources = repositoryManager.appResources
    private val localDefinitions by lazy(LazyThreadSafetyMode.PUBLICATION) {
        failOpen { moduleResourceManagers.localResourceManager.attributeDefinitions }
    }
    private val frameworkDefinitions by lazy(LazyThreadSafetyMode.PUBLICATION) {
        failOpen { moduleResourceManagers.frameworkResourceManager?.attributeDefinitions }
    }
    private val localAttributes by lazy(LazyThreadSafetyMode.PUBLICATION) {
        localDefinitions.readAttributes().filterNot { attribute ->
            attribute.definition.resourceReference.namespace == ResourceNamespace.ANDROID
        }
    }
    private val frameworkAttributes by lazy(LazyThreadSafetyMode.PUBLICATION) {
        frameworkDefinitions.readAttributes().filter { attribute ->
            attribute.definition.resourceReference.namespace == ResourceNamespace.ANDROID
        }
    }
    private val visibleFrameworkAttributes: List<Attribute>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val names = resourceNamesOrNull(ANDROID_NAMESPACE, ResourceType.ATTR)?.toSet() ?: return@lazy null
        frameworkAttributes.filter { attribute -> attribute.name in names }
    }
    private val visibleLocalAttributes: List<Attribute>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val localNamespace = repositoryManager.namespace
        buildList {
            localAttributes.groupBy { attribute -> attribute.definition.resourceReference.namespace }
                .forEach { (namespace, attributes) ->
                    val packageName = namespace.packageName
                    val resourceNamespace = if (namespace == localNamespace || packageName.isNullOrBlank())
                        APP_NAMESPACE
                    else packageName
                    val names = resourceNamesOrNull(resourceNamespace, ResourceType.ATTR)?.toSet() ?: return@lazy null
                    addAll(attributes.filter { attribute -> attribute.name in names })
                }
        }
    }

    /**
     * An Android attribute definition with its contributing styleable when known.
     * Value metadata is hidden when reusable-block consumers expose incompatible definitions.
     */
    data class Attribute(
        val definition: AttributeDefinition,
        val ownerStyleable: String? = null,
        val isValueCompletionAvailable: Boolean = true
    ) {

        /** The unqualified resource name. */
        val name get() = definition.name

        /** Whether the attribute is declared by the current project rather than Android or a library dependency. */
        val isProjectResource get() = definition.resourceReference.namespace != ResourceNamespace.ANDROID &&
            definition.libraryName == null

        /** The formats accepted by the Android resource definition. */
        val formats: Set<AttributeFormat>
            get() = definition.formats.takeIf { isValueCompletionAvailable }.orEmpty()

        /** The enum or flag values exported by the Android resource definition. */
        val values: List<String>
            get() = definition.values.orEmpty().takeIf { isValueCompletionAvailable }.orEmpty().sorted()

        /** Returns the Android description for [value], when available. */
        fun valueDescription(value: String) = failOpen { definition.getValueDescription(value) }
    }

    /**
     * A theme attribute reference offered to the Kotlin string completion contributor.
     */
    data class AttributeReference(
        val text: String,
        val attribute: Attribute
    )

    /**
     * A public Android resource reference accepted by an attribute value.
     */
    data class ResourceReferenceValue(
        val text: String,
        val reference: ResourceReference
    ) {

        /** The resource type presented by completion. */
        val type: ResourceType get() = reference.resourceType

        /** The unqualified resource name used as an additional lookup string. */
        val name: String get() = reference.name
    }

    /**
     * The common View scope and concrete consumer Views used to narrow attribute-name completion.
     */
    data class ViewScope(
        val viewClass: PsiClass,
        val consumerViewClasses: List<PsiClass>
    )

    /**
     * The parent ViewGroup classes whose layout styleables define `layout_*` completion.
     */
    data class LayoutScope(val parentViewClasses: List<PsiClass>)

    /**
     * The result of resolving an attribute against Android definitions.
     */
    sealed interface Resolution {

        /**
         * The attribute exists in the Android resource model.
         */
        data class Found(val attribute: Attribute) : Resolution

        /**
         * Android resource definitions were available, but the attribute does not exist.
         */
        data object NotFound : Resolution

        /**
         * Android resource definitions are not ready, so analysis must fail open.
         */
        data object Unavailable : Resolution
    }

    /** Resolves [name] in [namespace] without treating styleable membership as attribute ownership. */
    fun resolve(namespace: String, name: String, layoutScope: LayoutScope? = null): Resolution {
        val globalAttributes = attributesForNamespace(namespace) ?: return Resolution.Unavailable
        val globalAttribute = globalAttributes.firstOrNull { attribute -> attribute.name == name }
            ?: return Resolution.NotFound
        if (!name.startsWith(LAYOUT_ATTRIBUTE_PREFIX) || layoutScope == null)
            return Resolution.Found(globalAttribute)

        val scopedAttribute = collectLayoutAttributes(namespace, layoutScope)
            .attributes
            .firstOrNull { attribute -> attribute.name == name }
        return Resolution.Found(scopedAttribute ?: globalAttribute)
    }

    /** Resolves an Android theme attribute [reference], or null when the value is not a valid theme reference. */
    fun resolveAttributeReference(reference: String): Resolution? {
        val resourceUrl = failOpen { ResourceUrl.parse(reference) } ?: return null
        if (!resourceUrl.isTheme || resourceUrl.type != ResourceType.ATTR || !resourceUrl.hasValidName()) return null

        val resourceNamespace = resourceUrl.namespace
        val namespace = when {
            resourceUrl.isFramework -> ANDROID_NAMESPACE
            resourceNamespace.isNullOrBlank() -> APP_NAMESPACE
            else -> resourceNamespace
        }

        return resolve(namespace, resourceUrl.name)
    }

    /** Resolves an accessible Android resource or theme reference. */
    fun resolveResourceReference(reference: String): ResourceReference? {
        val resourceUrl = failOpen { ResourceUrl.parse(reference) } ?: return null
        if (resourceUrl.isCreate || !resourceUrl.hasValidName()) return null
        val resolved = failOpen {
            resourceUrl.resolve(repositoryManager.namespace, ResourceNamespace.Resolver.EMPTY_RESOLVER)
        } ?: return null
        return resolved.takeIf { resource ->
            isAccessible(resource.namespace, resource.resourceType, resource.name, facet) && resourceExists(resource)
        }
    }

    /** Returns whether [reference] has a concrete declaration owned by the current project. */
    fun isProjectResource(reference: ResourceReference): Boolean {
        if (reference.namespace == ResourceNamespace.ANDROID) return false
        val repository = if (reference.namespace == repositoryManager.namespace) appResources
        else failOpen { repositoryManager.getResourcesForNamespace(reference.namespace) }
            ?: return false

        return failOpen {
            repository.getResources(reference.namespace, reference.resourceType)
                .get(reference.name)
                .any { item -> item.resourceValue.isUserDefined }
        } == true
    }

    /** Returns attribute-name candidates for [namespace] and the optional View and parent-layout scopes. */
    fun attributes(namespace: String, viewScope: ViewScope?, layoutScope: LayoutScope?): List<Attribute> {
        val globalAttributes = attributesForNamespace(namespace) ?: return emptyList()
        val (globalLayoutAttributes, globalViewAttributes) = globalAttributes.partition { attribute ->
            attribute.name.startsWith(LAYOUT_ATTRIBUTE_PREFIX)
        }
        val viewAttributes = viewScope?.let { target ->
            collectScopedAttributes(namespace, target).takeIf(ScopedAttributes::hasStyleable)?.attributes
        } ?: globalViewAttributes
        val layoutAttributes = layoutScope?.let { target -> collectLayoutAttributes(namespace, target).attributes }
            ?: globalLayoutAttributes
        val visibleNames = globalAttributes.map(Attribute::name).toSet()

        return (viewAttributes + layoutAttributes)
            .filter { attribute -> attribute.name in visibleNames }
            .distinctBy(Attribute::name)
            .sortedBy(Attribute::name)
    }

    /** Returns theme attribute references from framework, project, and dependency definitions. */
    fun attributeReferences(): List<AttributeReference> {
        val references = visibleFrameworkAttributes.orEmpty().map { attribute ->
            AttributeReference(attribute.themeReference(), attribute)
        } + visibleLocalAttributes.orEmpty().map { attribute ->
            AttributeReference(attribute.themeReference(), attribute)
        }
        return references.distinctBy(AttributeReference::text).sortedBy(AttributeReference::text)
    }

    /** Returns resource types accepted by the same attribute converter used for Android XML. */
    fun acceptedResourceTypes(attribute: Attribute): Set<ResourceType> {
        val formats = attribute.formats
        if (AttributeFormat.FLAGS in formats) return emptySet()
        val matchingTypes = formats
            .mapNotNull(AndroidDomUtil::getResourceType)
            .toMutableSet()
        matchingTypes += AndroidDomUtil.getSpecialResourceTypes(attribute.name)
        if (AttributeFormat.REFERENCE in formats) {
            if (ResourceType.COLOR in matchingTypes) matchingTypes += ResourceType.DRAWABLE
            if (ResourceType.DRAWABLE in matchingTypes) matchingTypes += ResourceType.MIPMAP
            if (matchingTypes.isEmpty()) matchingTypes += ResourceType.REFERENCEABLE_TYPES
        }
        if (matchingTypes.isEmpty() && AttributeFormat.ENUM in formats) matchingTypes += ResourceType.INTEGER

        return matchingTypes.filter(ResourceType::getCanBeReferenced).toSet()
    }

    /**
     * Returns whether Android XML exposes resource references before the user types `@`.
     * A pure enum uses Android Studio's quiet integer-resource converter and therefore stays literal-only initially.
     */
    fun hasExpandedResourceCompletion(attribute: Attribute): Boolean {
        val formats = attribute.formats
        return AttributeFormat.FLAGS !in formats &&
            (formats.any { format -> AndroidDomUtil.getResourceType(format) != null } ||
                AndroidDomUtil.getSpecialResourceTypes(attribute.name).isNotEmpty() ||
                AttributeFormat.REFERENCE in formats)
    }

    /**
     * Returns complete public resource references accepted by [attribute].
     * A null [namespace] follows Android XML and searches every app/dependency namespace but not framework resources.
     */
    fun resourceReferences(attribute: Attribute, namespace: String? = null): List<ResourceReferenceValue> {
        val types = acceptedResourceTypes(attribute)
        if (types.isEmpty()) return emptyList()

        return resourceReferenceContexts(namespace).flatMap { context ->
            types.flatMap { type ->
                failOpen {
                    context.repository.getResourceItems(
                        context.namespace,
                        type,
                        ResourceVisibility.PUBLIC
                    )
                }.orEmpty().map { name ->
                    val reference = ResourceReference(context.namespace, type, name)
                    ResourceReferenceValue(
                        reference.getRelativeResourceUrl(repositoryManager.namespace).toString(),
                        reference
                    )
                }
            }
        }.distinctBy(ResourceReferenceValue::text).sortedBy(ResourceReferenceValue::text)
    }

    private fun resourceExists(reference: ResourceReference): Boolean {
        val repository = if (reference.namespace == repositoryManager.namespace) appResources
        else failOpen { repositoryManager.getResourcesForNamespace(reference.namespace) }
            ?: return false

        return failOpen {
            repository.getResources(reference.namespace, reference.resourceType)
                .get(reference.name)
                .isNotEmpty()
        } == true
    }

    private fun resourceNamesOrNull(namespace: String, type: ResourceType): List<String>? {
        val context = resourceContext(namespace)
            ?: return if (namespace == ANDROID_NAMESPACE || namespace == APP_NAMESPACE) null else emptyList()
        return failOpen {
            context.repository.getResourceItems(
                context.namespace,
                type,
                ResourceVisibility.PUBLIC
            )
        }?.sorted()
    }

    private fun attributesForNamespace(namespace: String): List<Attribute>? = when (namespace) {
        ANDROID_NAMESPACE -> frameworkDefinitions?.let { visibleFrameworkAttributes?.normalized() }
        APP_NAMESPACE -> localDefinitions?.let { visibleLocalAttributes?.normalized() }
        else -> localDefinitions?.let {
            visibleLocalAttributes?.filter { attribute ->
                attribute.definition.resourceReference.namespace.packageName == namespace
            }?.normalized()
        }
    }

    private fun collectScopedAttributes(namespace: String, target: ViewScope): ScopedAttributes {
        val parentAttributes = collectClassAttributes(namespace, target.viewClass)
        val consumers = target.consumerViewClasses.distinctBy(PsiClass::getQualifiedName)
        if (consumers.size <= 1) return parentAttributes

        val consumerAttributes = consumers.map { consumer -> collectClassAttributes(namespace, consumer) }
        return consumerAttributes.intersectAttributes(parentAttributes)
    }

    private fun collectLayoutAttributes(namespace: String, target: LayoutScope): ScopedAttributes {
        val parents = target.parentViewClasses.distinctBy(PsiClass::getQualifiedName)
        if (parents.isEmpty()) return ScopedAttributes(emptyList(), false)

        return parents.map { parent -> collectClassLayoutAttributes(namespace, parent) }.intersectAttributes()
    }

    private fun List<ScopedAttributes>.intersectAttributes(
        base: ScopedAttributes = ScopedAttributes(emptyList(), false)
    ): ScopedAttributes {
        if (isEmpty() || any { attributes -> !attributes.hasStyleable }) return base

        val commonNames = this
            .map { attributes -> attributes.attributes.map(Attribute::name).toSet() }
            .reduce(Set<String>::intersect)
        val merged = linkedMapOf<String, Attribute>()
        base.attributes.forEach { attribute -> merged.putIfAbsent(attribute.name, attribute) }
        val attributesByName = map { attributes ->
            attributes.attributes.associateBy(Attribute::name)
        }
        commonNames.sorted().forEach { name ->
            val candidates = attributesByName.mapNotNull { attributes -> attributes[name] }
            val representative = merged[name] ?: candidates.first()
            val hasConsistentValueContract = (listOfNotNull(merged[name]) + candidates)
                .all { attribute -> representative.hasSameValueContract(attribute) }
            merged[name] = representative.copy(
                isValueCompletionAvailable = representative.isValueCompletionAvailable && hasConsistentValueContract
            )
        }

        return ScopedAttributes(merged.values.toList(), base.hasStyleable || commonNames.isNotEmpty())
    }

    private fun collectClassAttributes(namespace: String, target: PsiClass): ScopedAttributes {
        var hasStyleable = false
        val attributes = linkedMapOf<String, Attribute>()

        generateSequence(target) { current -> current.superClass }.forEach { viewClass ->
            listOfNotNull(viewClass, viewClass.additionalAttributesClass()).forEach attributeClassLoop@{ attributeClass ->
                val className = attributeClass.name ?: return@attributeClassLoop
                val definitions = (if (attributeClass.isFrameworkClass()) frameworkDefinitions else localDefinitions)
                    ?: return@attributeClassLoop
                val styleable = definitions.findStyleable(className) ?: return@attributeClassLoop
                hasStyleable = true
                styleable.attributes
                    .asSequence()
                    .filter { definition -> definition.resourceReference.matchesNamespace(namespace) }
                    .forEach { definition ->
                        attributes.putIfAbsent(definition.name, Attribute(definition, styleable.name))
                    }
            }
        }
        return ScopedAttributes(attributes.values.toList(), hasStyleable)
    }

    private fun collectClassLayoutAttributes(namespace: String, target: PsiClass): ScopedAttributes {
        var hasStyleable = false
        val attributes = linkedMapOf<String, Attribute>()

        generateSequence(target) { current -> current.superClass }.forEach { parentClass ->
            val definitions = (if (parentClass.isFrameworkClass()) frameworkDefinitions else localDefinitions)
                ?: return@forEach
            listOfNotNull(
                failOpen { AttributeProcessingUtil.getLayoutStyleablePrimary(parentClass) },
                failOpen { AttributeProcessingUtil.getLayoutStyleableSecondary(parentClass) }
            ).forEach styleableLoop@{ styleableName ->
                val styleable = definitions.findStyleable(styleableName) ?: return@styleableLoop
                hasStyleable = true
                styleable.attributes
                    .asSequence()
                    .filter { definition -> definition.name.startsWith(LAYOUT_ATTRIBUTE_PREFIX) }
                    .filter { definition -> definition.resourceReference.matchesNamespace(namespace) }
                    .forEach { definition ->
                        attributes.putIfAbsent(definition.name, Attribute(definition, styleable.name))
                    }
            }
        }
        return ScopedAttributes(attributes.values.toList(), hasStyleable)
    }

    private fun PsiClass.additionalAttributesClass(): PsiClass? {
        if (!AndroidXConstants.CLASS_NESTED_SCROLL_VIEW.isEquals(qualifiedName.orEmpty())) return null

        // AttributeProcessingUtil keeps its only additional View mapping private. Mirror that XML
        // rule through the same AndroidX symbol and XML-visible class lookup used by Android Studio.
        return findViewValidInXMLByName(facet, SCROLL_VIEW_NAME)
    }

    private fun AttributeDefinitions?.readAttributes() = this?.attrs
        ?.mapNotNull { reference -> failOpen { getAttrDefinition(reference) }?.let(::Attribute) }
        .orEmpty()

    private fun AttributeDefinitions.findStyleable(name: String) =
        // This is the current AttributeProcessingUtil getStyleableByName priority, replayed through
        // the namespace-aware API so same-named dependency styleables are never merged or guessed.
        failOpen { getStyleableDefinition(ResourceReference.styleable(ResourceNamespace.TODO(), name)) }
            ?: failOpen { getStyleableDefinition(ResourceReference.styleable(ResourceNamespace.ANDROID, name)) }

    private fun List<Attribute>.normalized() = sortedWith(
        compareBy<Attribute> { attribute ->
            if (attribute.definition.resourceReference.namespace == repositoryManager.namespace) 0 else 1
        }.thenBy(Attribute::name)
            .thenBy { attribute -> attribute.definition.resourceReference.namespace.toString() }
    ).distinctBy(Attribute::name)

    private fun Attribute.hasSameValueContract(other: Attribute) = definition.formats == other.definition.formats &&
        definition.values.orEmpty().toSet() == other.definition.values.orEmpty().toSet()

    private fun ResourceReference.matchesNamespace(namespace: String) = when (namespace) {
        ANDROID_NAMESPACE -> this.namespace == ResourceNamespace.ANDROID
        APP_NAMESPACE -> this.namespace != ResourceNamespace.ANDROID
        else -> this.namespace != ResourceNamespace.ANDROID && this.namespace.packageName == namespace
    }

    private fun Attribute.themeReference(): String {
        val relativeUrl = definition.resourceReference.getRelativeResourceUrl(repositoryManager.namespace)
        return ResourceUrl.createThemeReference(relativeUrl.namespace, ResourceType.ATTR, name).toString()
    }

    private fun PsiClass.isFrameworkClass(): Boolean {
        val qualifiedName = qualifiedName ?: return false
        return qualifiedName.startsWith("android.") &&
            !qualifiedName.startsWith("android.support.") &&
            !qualifiedName.startsWith("android.arch.")
    }

    private fun resourceContext(namespace: String): ResourceContext? {
        val resourceNamespace = when (namespace) {
            ANDROID_NAMESPACE -> ResourceNamespace.ANDROID
            APP_NAMESPACE -> repositoryManager.namespace
            else -> failOpen { ResourceNamespace.fromPackageName(namespace) } ?: return null
        }
        val repository = if (namespace == APP_NAMESPACE) appResources
        else failOpen { repositoryManager.getResourcesForNamespace(resourceNamespace) }
        repository ?: return null

        return ResourceContext(resourceNamespace, repository)
    }

    private fun resourceReferenceContexts(namespace: String?): List<ResourceContext> {
        if (namespace == null) return failOpen { appResources.namespaces }
            .orEmpty()
            .map { resourceNamespace -> ResourceContext(resourceNamespace, appResources) }

        val resourceNamespace = if (namespace == ANDROID_NAMESPACE) ResourceNamespace.ANDROID
        else failOpen { ResourceNamespace.fromPackageName(namespace) } ?: return emptyList()
        val repository = failOpen { repositoryManager.getResourcesForNamespace(resourceNamespace) } ?: return emptyList()
        return listOf(ResourceContext(resourceNamespace, repository))
    }

    private data class ScopedAttributes(
        val attributes: List<Attribute>,
        val hasStyleable: Boolean
    )

    private data class ResourceContext(
        val namespace: ResourceNamespace,
        val repository: ResourceRepository
    )
}