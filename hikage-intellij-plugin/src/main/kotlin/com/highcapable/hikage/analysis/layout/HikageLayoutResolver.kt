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
 * This file is created by fankes on 2026/7/22.
 */
package com.highcapable.hikage.analysis.layout

import com.highcapable.hikage.analysis.layout.helper.HikageLayoutIdHelper
import com.highcapable.hikage.analysis.layout.helper.HikageLayoutSourceHelper
import com.highcapable.hikage.analysis.layout.helper.HikageLayoutSourceHelper.Source
import com.highcapable.hikage.analysis.layout.helper.HikageLayoutTypeHelper
import com.highcapable.hikage.analysis.layout.model.HikageLayout
import com.highcapable.hikage.analysis.layout.model.HikageLayout.Id
import com.highcapable.hikage.analysis.layout.model.HikageLayoutLookup
import com.highcapable.hikage.symbol.AndroidSymbols
import com.highcapable.hikage.symbol.HikageSymbols
import com.highcapable.hikage.utils.extension.failOpen
import com.highcapable.hikage.utils.extension.findArgument
import com.highcapable.hikage.utils.extension.resolveMethod
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiClass
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Resolves one Hikage receiver into the shared layout model consumed by editor features.
 */
class HikageLayoutResolver private constructor(project: Project) {

    companion object {

        private const val ID_ARGUMENT = "id"

        /**
         * Creates a layout-ID resolver for [project].
         * @param project the current IDE project.
         * @return [HikageLayoutResolver]
         */
        fun from(project: Project) = HikageLayoutResolver(project)
    }

    private val typeHelper = HikageLayoutTypeHelper(project)
    private val sourceHelper = HikageLayoutSourceHelper(typeHelper)
    private val idHelper = HikageLayoutIdHelper(sourceHelper, typeHelper)
    private val rootTracker = ProjectRootModificationTracker.getInstance(project)
    private val dumbTracker = DumbService.getInstance(project).modificationTracker

    /** Returns whether [receiver] is a real Hikage runtime value. */
    fun isHikage(receiver: KtExpression) = failOpen { typeHelper.isHikage(receiver) } == true

    /** Resolves the class represented by [typeReference] without exposing the helper layer. */
    fun resolveTypeClass(typeReference: KtTypeReference) = failOpen { typeHelper.resolveTypeClass(typeReference) }

    /** Creates a Kotlin source reference for [viewClass] in [file]. */
    fun createTypeReference(file: KtFile, viewClass: PsiClass) = typeHelper.createTypeReference(file, viewClass)

    /** Resolves a statically known layout ID passed through [expression]. */
    fun resolveIdValue(expression: KtExpression) = failOpen { idHelper.resolveIdValue(expression) }

    /**
     * Resolves an array-access or explicit `get`/`getOrNull` call to its declared layout ID.
     * @param expression the lookup expression or its call selector.
     * @return [HikageLayoutLookup.Id] when the receiver, ID value, and declaration are all known.
     */
    fun resolveIdLookup(expression: KtExpression): HikageLayoutLookup.Id? {
        val (lookupExpression, receiver, idExpression) = expression.findDirectLayoutIdLookupParts() ?: return null
        val id = resolveIdValue(idExpression) ?: return null
        val layoutId = resolve(receiver)?.ids?.firstOrNull { candidate -> candidate.name == id } ?: return null

        return HikageLayoutLookup.Id(lookupExpression, receiver, idExpression, layoutId)
    }

    /**
     * Resolves the actual lookup calls fed by the static ID value [expression].
     * @param expression a direct lookup value or an immutable local/constant property initializer.
     * @return resolved lookup calls, excluding non-lookup uses such as DSL ID declarations.
     */
    fun resolveIdLookupsFromValue(expression: KtExpression): List<HikageLayoutLookup.Id> {
        resolveIdLookup(expression)?.let { return listOf(it) }
        val property = expression.stableIdValueProperty() ?: return emptyList()
        val scope = property.idValueUseScope() ?: return emptyList()

        return ReferencesSearch.search(property, scope, false).findAll()
            .asSequence()
            .map { reference -> reference.element }
            .filterIsInstance<KtExpression>()
            .mapNotNull(::resolveIdLookup)
            .distinctBy { lookup -> lookup.expression }
            .toList()
    }

    /** Resolves every layout ID represented by the component performer name [expression]. */
    fun resolveIdDeclarations(expression: KtExpression): List<Id> {
        val source = failOpen { sourceHelper.findContainingSource(expression) } ?: return emptyList()
        return resolve(source).ids.filter { candidate ->
            candidate.performer === expression ||
                candidate.performer.manager.areElementsEquivalent(candidate.performer, expression)
        }
    }

    /** Resolves [expression] when it is the component performer name declaring a layout ID. */
    fun resolveIdDeclaration(expression: KtExpression) = resolveIdDeclarations(expression).firstOrNull()

    /** Resolves [expression] when it directly supplies a declared layout ID value. */
    fun resolveIdValueDeclaration(expression: KtExpression): Id? {
        val source = failOpen { sourceHelper.findContainingSource(expression) } ?: return null
        return resolve(source).ids.firstOrNull { candidate ->
            candidate.declaration === expression ||
                candidate.declaration.manager.areElementsEquivalent(candidate.declaration, expression)
        }
    }

    /**
     * Resolves an explicit `root()` call to the root View declared by its Hikage receiver.
     * @param expression the qualified root expression or its call selector.
     * @return [HikageLayoutLookup.Root] when the receiver and root declaration are both known.
     */
    fun resolveRootLookup(expression: KtExpression): HikageLayoutLookup.Root? {
        val (lookupExpression, receiver, call) = expression.layoutRootLookupParts() ?: return null
        val layoutRoot = resolve(receiver)?.root ?: return null
        val typeReference = call.typeArgumentList?.arguments?.singleOrNull()?.typeReference

        return HikageLayoutLookup.Root(lookupExpression, receiver, call, typeReference, layoutRoot)
    }

    /** Returns whether [lookup] explicitly requests a type incompatible with its declared View. */
    fun hasIncorrectLookupType(lookup: HikageLayoutLookup.Id): Boolean {
        val viewClass = lookup.layoutId.viewClass ?: return false
        val qualified = lookup.expression as? KtQualifiedExpression ?: return false
        val call = qualified.selectorExpression as? KtCallExpression ?: return false
        val typeReference = call.typeArgumentList?.arguments?.singleOrNull()?.typeReference ?: return false

        return isIncorrectLookupType(typeReference, viewClass)
    }

    /** Returns whether [lookup] explicitly requests a type incompatible with its declared root View. */
    fun hasIncorrectLookupType(lookup: HikageLayoutLookup.Root) = lookup.typeReference
        ?.let { typeReference -> isIncorrectLookupType(typeReference, lookup.layoutRoot.viewClass) }
        ?: false

    /** Returns whether [typeReference] is incompatible with the declared [viewClass]. */
    fun isIncorrectLookupType(typeReference: KtTypeReference, viewClass: PsiClass): Boolean {
        val currentClass = resolveTypeClass(typeReference) ?: return false
        return currentClass.qualifiedName != AndroidSymbols.VIEW_CLASS && !viewClass.canCastTo(currentClass)
    }

    /**
     * Finds the direct Hikage or Delegate performer scope lexically containing [expression].
     *
     * Each resolved scope owns its IDs independently; IDs from separate layouts must not be merged.
     */
    fun findDeclarationScope(expression: KtExpression) = failOpen { sourceHelper.findContainingSource(expression)?.anchor }

    /** Resolves [receiver] when it is a real Hikage instance with statically traceable source. */
    fun resolve(receiver: KtExpression): HikageLayout? {
        if (!isHikage(receiver)) return null
        val sources = failOpen { sourceHelper.resolve(receiver) }.orEmpty()
        if (sources.isEmpty()) return null

        val models = sources.map(::resolve)
        return merge(models)
    }

    private fun resolve(source: Source) = CachedValuesManager.getCachedValue(source.anchor) {
        CachedValueProvider.Result.create(
            idHelper.resolve(source),
            PsiModificationTracker.MODIFICATION_COUNT,
            rootTracker,
            dumbTracker
        )
    }

    private fun merge(models: List<HikageLayout>): HikageLayout {
        val alwaysPresentIds = models
            .map { model ->
                model.ids.filter(Id::isAlwaysPresent).map(Id::name).toSet()
            }
            .reduceOrNull { commonIds, ids -> commonIds intersect ids }
            .orEmpty()
        val ids = models.flatMap(HikageLayout::ids)
            .groupBy(Id::name)
            .map { (name, declarations) ->
                val classes = declarations.map(Id::viewClass)
                    .distinctBy { viewClass -> viewClass?.qualifiedName ?: viewClass }
                declarations.first().copy(
                    viewClass = classes.singleOrNull(),
                    isAlwaysPresent = name in alwaysPresentIds
                )
            }
        val roots = models.mapNotNull(HikageLayout::root)
        val rootClasses = roots.distinctBy { root -> root.viewClass.qualifiedName ?: root.viewClass }
        val root = roots.firstOrNull()?.takeIf { roots.size == models.size && rootClasses.size == 1 }

        return HikageLayout(ids, root)
    }

    private fun KtExpression.layoutIdLookupParts(): Triple<KtExpression, KtExpression, KtExpression>? = when (this) {
        is KtArrayAccessExpression -> {
            val receiver = arrayExpression ?: return null
            val idExpression = indexExpressions.singleOrNull() ?: return null

            Triple(this, receiver, idExpression)
        }
        is KtCallExpression -> {
            val qualified = parent as? KtQualifiedExpression ?: return null
            if (qualified.selectorExpression !== this) return null

            qualified.layoutIdLookupParts()
        }
        is KtQualifiedExpression -> {
            if (operationSign != KtTokens.DOT) return null
            val call = selectorExpression as? KtCallExpression ?: return null
            val idExpression = call.layoutIdExpression() ?: return null

            Triple(this, receiverExpression, idExpression)
        }
        else -> null
    }

    private fun KtExpression.findDirectLayoutIdLookupParts() = layoutIdLookupParts()
        ?: generateSequence(parent) { element -> element.parent }
            .filterIsInstance<KtExpression>()
            .mapNotNull { expression -> expression.layoutIdLookupParts() }
            .firstOrNull { (_, _, idExpression) -> PsiTreeUtil.isAncestor(idExpression, this, false) }

    private fun KtExpression.stableIdValueProperty() = (parent as? KtProperty)?.takeIf { property ->
        property.initializer === this && !property.isVar &&
            (property.parent is KtBlockExpression || property.hasModifier(KtTokens.CONST_KEYWORD))
    }

    private fun KtProperty.idValueUseScope() = (parent as? KtBlockExpression)
        ?.let(::LocalSearchScope)
        ?: useScope.takeIf { hasModifier(KtTokens.CONST_KEYWORD) }

    private fun KtExpression.layoutRootLookupParts(): Triple<KtExpression, KtExpression, KtCallExpression>? {
        val qualified = when (this) {
            is KtCallExpression -> (parent as? KtQualifiedExpression)
                ?.takeIf { expression -> expression.selectorExpression === this }
            is KtQualifiedExpression -> this
            else -> null
        } ?: return null
        if (qualified.operationSign != KtTokens.DOT) return null

        val call = qualified.selectorExpression as? KtCallExpression ?: return null
        if (call.valueArguments.isNotEmpty() || call.typeArgumentList?.arguments.orEmpty().size > 1 ||
            call.calleeExpression?.text != HikageSymbols.HIKAGE_ROOT_FUNCTION_NAME
        ) return null

        val method = call.resolveMethod() ?: return null
        if (method.containingClass?.qualifiedName != HikageSymbols.HIKAGE) return null

        return Triple(qualified, qualified.receiverExpression, call)
    }

    private fun KtCallExpression.layoutIdExpression(): KtExpression? {
        if (typeArgumentList?.arguments.orEmpty().size > 1) return null
        if (calleeExpression?.text != HikageSymbols.HIKAGE_GET_FUNCTION_NAME &&
            calleeExpression?.text != HikageSymbols.HIKAGE_GET_OR_NULL_FUNCTION_NAME
        ) return null

        val method = resolveMethod() ?: return null
        if (method.containingClass?.qualifiedName != HikageSymbols.HIKAGE) return null

        return findArgument(method, ID_ARGUMENT)?.getArgumentExpression()
    }

    private fun PsiClass.canCastTo(target: PsiClass) = this == target ||
        qualifiedName == target.qualifiedName ||
        isInheritor(target, true)
}