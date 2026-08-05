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
 * This file is created by fankes on 2026/7/30.
 */
package com.highcapable.hikage.convert.planner

import com.android.ide.common.rendering.api.AttributeFormat
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Argument
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.MemberKind
import com.highcapable.hikage.convert.model.KotlinLayoutInitializer.Value
import com.highcapable.hikage.convert.model.XmlLayoutAttribute
import com.highcapable.hikage.project.model.android.AndroidAttributeResolver
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Proves ordinary XML attributes against inspection metadata or direct `TypedArray` initialization flow.
 */
object ViewAttributePlanner {

    private const val FRAMEWORK_PACKAGE_PREFIX = "android."
    private const val FRAMEWORK_NAMESPACE = "android"
    private const val APP_NAMESPACE = "app"
    private const val TYPED_ARRAY_CLASS = "android.content.res.TypedArray"
    private const val ANDROID_INSPECTABLE_PROPERTY = "android.view.inspector.InspectableProperty"
    private const val ANDROIDX_INSPECTABLE_PROPERTY = "androidx.annotation.InspectableProperty"
    private const val ANDROIDX_RESOURCE_ATTRIBUTE = "androidx.resourceinspection.annotation.Attribute"
    private const val ANDROID_INSPECTABLE_VALUE_TYPE = "$ANDROID_INSPECTABLE_PROPERTY.ValueType"
    private const val ANDROIDX_INSPECTABLE_VALUE_TYPE = "$ANDROIDX_INSPECTABLE_PROPERTY.ValueType"
    private const val ANDROIDX_GRAVITY_INT = "androidx.annotation.GravityInt"
    private const val ANDROID_GRAVITY_CLASS = "android.view.Gravity"
    private const val ANDROIDX_COLOR_TO_DRAWABLE = "androidx.core.graphics.drawable.toDrawable"
    private const val ANDROIDX_VIEW_IS_VISIBLE = "androidx.core.view.isVisible"
    private const val ANDROIDX_VIEW_IS_INVISIBLE = "androidx.core.view.isInvisible"
    private const val VISIBILITY_ATTRIBUTE = "visibility"

    private val DRAWABLE_RESOURCE_ANNOTATIONS = setOf(
        "android.annotation.DrawableRes",
        "androidx.annotation.DrawableRes"
    )
    private val INT_DEF_ANNOTATIONS = setOf(
        "android.annotation.IntDef",
        "androidx.annotation.IntDef"
    )

    private val INTEGER_MAPPING_ATTRIBUTES = listOf("enumMapping", "flagMapping", "intMapping")

    private val INTEGER_VALUE = "[-+]?(?:[0-9]+|0[xX][0-9a-fA-F]+)".toRegex()
    private val FLOAT_VALUE = "[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?".toRegex()
    private val DP_VALUE = "([-+]?[0-9]+)(?:dp|dip)".toRegex(RegexOption.IGNORE_CASE)
    private val PX_VALUE = "([-+]?[0-9]+)px".toRegex(RegexOption.IGNORE_CASE)
    private val RESOURCE_VALUE = "^@(?:([A-Za-z_][A-Za-z0-9_.]*):)?([a-z][a-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)$".toRegex()
    private val DOCUMENTED_ATTRIBUTE = "(?:([A-Za-z_][A-Za-z0-9_.]*)\\.)?R\\.(styleable|attr)#([A-Za-z_][A-Za-z0-9_]*)".toRegex()

    /**
     * Returns a proven initializer for [attribute], or null when member identity or value conversion is uncertain.
     * @param attribute the neutral XML View attribute.
     * @param namespace the normalized Android resource namespace.
     * @param viewClass the resolved concrete View class.
     * @param definition the Android Studio attribute definition when available.
     * @param resourcePackageName the current module namespace for unqualified local resource references.
     * @param themeAttributeDefinition the referenced theme attr definition when View `init` exposes a `Context`.
     */
    fun plan(
        attribute: XmlLayoutAttribute,
        namespace: String,
        viewClass: PsiClass,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?,
        themeAttributeDefinition: AndroidAttributeResolver.Attribute? = null
    ): KotlinLayoutInitializer? {
        if (namespace == FRAMEWORK_NAMESPACE && attribute.localName == VISIBILITY_ATTRIBUTE) return attribute.planFrameworkVisibility()
        val annotatedProofs = viewClass.annotatedMemberProofs(attribute.localName, namespace)
        val proofs = buildList {
            addAll(annotatedProofs)
            if (annotatedProofs.isEmpty())
                addAll(viewClass.documentedSetterProofs(attribute.localName, namespace))
            addAll(viewClass.typedArrayProofs(attribute.localName, namespace))
        }.distinctBy(MemberProof::identity)

        return proofs.mapNotNull { proof ->
            if (proof.requiresExactIntegerNames && !proof.integerMetadata.matchesDefinitionValues(definition)) return@mapNotNull null
            val value = proof.valueKind.convert(
                rawValue = attribute.value,
                targetType = proof.targetType,
                definition = definition,
                resourcePackageName = resourcePackageName,
                resourceTypes = proof.resourceTypes,
                integerMetadata = proof.integerMetadata,
                themeAttributeDefinition = themeAttributeDefinition
            ) ?: return@mapNotNull null
            if (proof.rejectsNullValue && value == Value.Null) return@mapNotNull null

            KotlinLayoutInitializer(
                memberName = proof.memberName,
                memberKind = proof.memberKind,
                arguments = (0 until proof.argumentCount).map { index ->
                    Argument(value = if (index == proof.valueArgumentIndex) value else proof.fixedArguments.getValue(index))
                }
            )
        }.singleOrNull()
    }

    private fun XmlLayoutAttribute.planFrameworkVisibility(): KotlinLayoutInitializer? {
        // XML uses inflater ordinals 0/1/2, while View.visibility accepts 0/4/8.
        val (memberName, importName, isEnabled) = when (value) {
            "visible" -> Triple("isVisible", ANDROIDX_VIEW_IS_VISIBLE, true)
            "invisible" -> Triple("isInvisible", ANDROIDX_VIEW_IS_INVISIBLE, true)
            "gone" -> Triple("isVisible", ANDROIDX_VIEW_IS_VISIBLE, false)
            else -> return null
        }
        return KotlinLayoutInitializer(
            memberName = memberName,
            memberKind = MemberKind.PROPERTY,
            arguments = listOf(Argument(value = Value.BooleanLiteral(isEnabled))),
            importName = importName
        )
    }

    private fun PsiClass.annotatedMemberProofs(attributeName: String, namespace: String) =
        generateSequence(this) { current -> current.superClass }
            .flatMap { current -> sequenceOf(*current.methods, *current.fields) }
            .mapNotNull { member -> member.annotatedMemberProof(attributeName, namespace, this) }
            .toList()

    private fun PsiMember.annotatedMemberProof(
        attributeName: String,
        namespace: String,
        targetClass: PsiClass
    ): MemberProof? {
        if (!hasModifierProperty(PsiModifier.PUBLIC) || hasModifierProperty(PsiModifier.STATIC)) return null
        val annotatedAttribute = annotatedAttribute(attributeName, namespace) ?: return null

        return when (this) {
            is PsiField -> takeUnless { field -> field.hasModifierProperty(PsiModifier.FINAL) }?.let { field ->
                MemberProof(
                    field.name,
                    MemberKind.PROPERTY,
                    field.type,
                    ValueKind.TARGET_TYPE,
                    integerMetadata = integerMetadata(annotatedAttribute.annotation, field)
                )
            }
            is PsiMethod -> when {
                PropertyUtilBase.isSimplePropertyGetter(this) -> {
                    val targetType = returnType ?: return null
                    val propertyName = PropertyUtilBase.getPropertyNameByGetter(this)
                    val setter = PropertyUtilBase.findPropertySetterWithType(
                        propertyName,
                        false,
                        targetType,
                        targetClass.allMethods.toList()
                    )?.takeIf { method -> method.hasModifierProperty(PsiModifier.PUBLIC) }
                    if (setter == null) return targetClass.documentedSetterForAnnotatedGetter(
                        attributeName,
                        namespace,
                        targetType,
                        annotatedAttribute.annotation,
                        this
                    )
                    val parameter = setter.parameterList.parameters.single()
                    MemberProof(
                        memberName = kotlinPropertyName(propertyName, targetType),
                        memberKind = MemberKind.PROPERTY,
                        targetType = parameter.type,
                        valueKind = ValueKind.TARGET_TYPE,
                        integerMetadata = integerMetadata(annotatedAttribute.annotation, this, parameter)
                    )
                }
                PropertyUtilBase.isSimplePropertySetter(this) -> MemberProof(
                    name,
                    MemberKind.METHOD,
                    parameterList.parameters.single().type,
                    ValueKind.TARGET_TYPE,
                    integerMetadata = integerMetadata(
                        annotatedAttribute.annotation,
                        this,
                        parameterList.parameters.single()
                    )
                )
                else -> null
            }
            else -> null
        }
    }

    private fun PsiClass.documentedSetterForAnnotatedGetter(
        attributeName: String,
        namespace: String,
        targetType: PsiType,
        attributeAnnotation: PsiAnnotation,
        getter: PsiMethod
    ): MemberProof? {
        val setter = generateSequence(this) { current -> current.superClass }
            .flatMap { current -> current.methods.asSequence() }
            .filter { method ->
                method.hasModifierProperty(PsiModifier.PUBLIC) &&
                    !method.hasModifierProperty(PsiModifier.STATIC) &&
                    PropertyUtilBase.isSimplePropertySetter(method) &&
                    method.referencesAttribute(attributeName, namespace) &&
                    method.parameterList.parameters.single().type == targetType
            }
            .distinctBy { method -> method.containingClass?.qualifiedName to method.name }
            .singleOrNull() ?: return null
        val parameter = setter.parameterList.parameters.single()
        val sourceParameter = (setter.navigationElement as? PsiMethod)?.parameterList?.parameters?.singleOrNull()

        return MemberProof(
            memberName = setter.name,
            memberKind = MemberKind.METHOD,
            targetType = parameter.type,
            valueKind = ValueKind.TARGET_TYPE,
            integerMetadata = integerMetadata(
                attributeAnnotation,
                getter,
                setter,
                parameter,
                sourceParameter
            )
        )
    }

    private fun PsiMethod.kotlinPropertyName(javaBeanName: String, targetType: PsiType) =
        if (targetType.isBooleanType() && name.length > 2 && name.startsWith("is") && name[2].isUpperCase()) name
        else javaBeanName

    private fun PsiMember.annotatedAttribute(
        attributeName: String,
        namespace: String
    ): AnnotatedAttributeProof? {
        val proofs = sequenceOf(this, navigationElement as? PsiModifierListOwner)
            .filterNotNull()
            .distinct()
            .flatMap { owner -> owner.annotations.asSequence() }
            .mapNotNull { annotation -> annotation.annotatedAttributeProof(this) }
        return proofs.firstOrNull { proof ->
            proof.name == attributeName && (proof.namespace?.let(namespace::equals) ?: matchesNamespace(namespace))
        }
    }

    private fun PsiAnnotation.annotatedAttributeProof(owner: PsiModifierListOwner): AnnotatedAttributeProof? {
        return when {
            matchesAnnotation(ANDROID_INSPECTABLE_PROPERTY) || matchesAnnotation(ANDROIDX_INSPECTABLE_PROPERTY) -> {
                val name = stringValue("name")?.takeIf(String::isNotBlank) ?: when (owner) {
                    is PsiField -> owner.name
                    is PsiMethod -> PropertyUtilBase.getPropertyName(owner)
                    else -> null
                } ?: return null
                AnnotatedAttributeProof(name, null, this)
            }
            matchesAnnotation(ANDROIDX_RESOURCE_ATTRIBUTE) -> {
                val value = stringValue("value")?.trim()?.takeIf(String::isNotEmpty) ?: return null
                val separator = value.indexOf(':')
                val namespace = if (separator < 0) APP_NAMESPACE else value.substring(0, separator)
                val name = if (separator < 0) value else value.substring(separator + 1)
                if (namespace.isBlank() || name.isBlank() || ':' in name) return null
                AnnotatedAttributeProof(name, namespace, this)
            }
            else -> null
        }
    }

    private fun PsiAnnotation.stringValue(name: String) = findAttributeValue(name)?.let { expression ->
        JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(expression) as? String
    }

    private fun PsiAnnotation.matchesAnnotation(qualifiedName: String): Boolean {
        if (this.qualifiedName == qualifiedName || nameReferenceElement?.text == qualifiedName) return true
        if (nameReferenceElement?.referenceName != qualifiedName.substringAfterLast('.')) return false
        return (containingFile as? PsiJavaFile)?.importList?.importStatements?.any { statement ->
            !statement.isOnDemand && statement.qualifiedName == qualifiedName
        } == true
    }

    private fun integerMetadata(
        attributeAnnotation: PsiAnnotation?,
        vararg owners: PsiModifierListOwner?
    ) = IntegerMetadata(
        fields = buildList {
            attributeAnnotation?.mappedIntegerFields()?.let(::addAll)
            attributeAnnotation?.inspectableGravityFields()?.let(::addAll)
            owners.filterNotNull().forEach { owner ->
                addAll(owner.intDefFields())
                addAll(owner.gravityFields())
            }
        }.distinctBy { field -> field.symbolIdentity() },
        namedFields = attributeAnnotation?.namedIntegerFields().orEmpty(),
        requiresAttributeValueMapping = attributeAnnotation?.matchesAnnotation(ANDROIDX_RESOURCE_ATTRIBUTE) == true
    )

    private fun PsiAnnotation.mappedIntegerFields() = INTEGER_MAPPING_ATTRIBUTES
        .flatMap { name -> findAttributeValue(name)?.resolvedFields().orEmpty() }

    private fun PsiAnnotation.namedIntegerFields(): Map<String, List<PsiField>> {
        val fields = linkedMapOf<String, List<PsiField>>()
        INTEGER_MAPPING_ATTRIBUTES.forEach { mappingName ->
            findAttributeValue(mappingName)?.elementsDepthFirst()
                ?.filterIsInstance<PsiAnnotation>()
                ?.forEach { annotation ->
                    val name = annotation.stringValue("name")
                    val mappedFields = listOf("value", "target").asSequence()
                        .mapNotNull(annotation::findAttributeValue)
                        .flatMap { value -> value.resolvedFields().asSequence() }
                        .distinctBy { field -> field.symbolIdentity() }
                        .toList()
                    if (name != null && mappedFields.isNotEmpty()) fields[name] = mappedFields
                }
        }
        return fields
    }

    private fun PsiAnnotation.inspectableGravityFields(): List<PsiField> {
        val valueTypeValue = findAttributeValue("valueType") ?: return emptyList()
        val valueType = valueTypeValue.resolvedFields().singleOrNull()
        val isGravity = if (valueType != null)
            valueType.name == "GRAVITY" && valueType.containingClass?.qualifiedName in setOf(
                ANDROID_INSPECTABLE_VALUE_TYPE,
                ANDROIDX_INSPECTABLE_VALUE_TYPE
            )
        else {
            // Android's public SDK omits this SOURCE annotation type even though the attached source retains its usage.
            (matchesAnnotation(ANDROID_INSPECTABLE_PROPERTY) || matchesAnnotation(ANDROIDX_INSPECTABLE_PROPERTY)) &&
                (valueTypeValue as? PsiReferenceExpression)?.referenceName == "GRAVITY"
        }
        if (!isGravity) return emptyList()
        return gravityConstantFields()
    }

    private fun PsiModifierListOwner.gravityFields(): List<PsiField> {
        val hasGravityContract = sequenceOf(this, navigationElement as? PsiModifierListOwner)
            .filterNotNull()
            .distinct()
            .flatMap { owner -> owner.annotations.asSequence() }
            .any { annotation -> annotation.matchesAnnotation(ANDROIDX_GRAVITY_INT) }
        if (!hasGravityContract) return emptyList()
        return gravityConstantFields()
    }

    private fun PsiElement.gravityConstantFields(): List<PsiField> {
        val gravityClass = JavaPsiFacade.getInstance(project).findClass(
            ANDROID_GRAVITY_CLASS,
            GlobalSearchScope.allScope(project)
        ) ?: return emptyList()
        val gravityFlags = sequenceOf(gravityClass, gravityClass.navigationElement as? PsiClass)
            .filterNotNull()
            .distinct()
            .firstNotNullOfOrNull { type -> type.findInnerClassByName("GravityFlags", false) }
            ?: return emptyList()
        return gravityFlags.intDefFields()
    }

    private fun PsiModifierListOwner.intDefFields() = sequenceOf(this, navigationElement as? PsiModifierListOwner)
        .filterNotNull()
        .distinct()
        .flatMap { owner -> owner.annotations.asSequence() }
        .flatMap { annotation -> annotation.intDefFields().asSequence() }
        .distinctBy { field -> field.symbolIdentity() }
        .toList()

    private fun PsiAnnotation.intDefFields(): List<PsiField> {
        val annotationType = resolveAnnotationType() ?: sourceOnlyNestedAnnotationType()
        val intDefs = buildList {
            if (INT_DEF_ANNOTATIONS.any { qualifiedName -> matchesAnnotation(qualifiedName) }) add(this@intDefFields)
            sequenceOf(annotationType, annotationType?.navigationElement as? PsiClass)
                .filterNotNull()
                .distinct()
                .flatMap { type -> type.annotations.asSequence() }
                .filter { annotation ->
                    INT_DEF_ANNOTATIONS.any { qualifiedName -> annotation.matchesAnnotation(qualifiedName) }
                }
                .forEach(::add)
        }
        return intDefs.flatMap { annotation ->
            annotation.findAttributeValue("value")?.resolvedFields().orEmpty()
        }
    }

    private fun PsiAnnotation.sourceOnlyNestedAnnotationType(): PsiClass? {
        val reference = nameReferenceElement ?: return null
        val owner = reference.qualifier?.reference?.resolve() as? PsiClass ?: return null
        val name = reference.referenceName ?: return null

        return sequenceOf(owner, owner.navigationElement as? PsiClass)
            .filterNotNull()
            .distinct()
            .mapNotNull { type -> type.findInnerClassByName(name, false) }
            .firstOrNull(PsiClass::isAnnotationType)
    }

    private fun PsiElement.resolvedFields() = elementsDepthFirst()
        .mapNotNull { element -> (element as? PsiReferenceExpression)?.resolve() as? PsiField }
        .distinctBy { field -> field.symbolIdentity() }
        .toList()

    // Walking visitors use sibling traversal that Android Studio rejects for compiled annotation PSI.
    private fun PsiElement.elementsDepthFirst(): Sequence<PsiElement> = sequence {
        yield(this@elementsDepthFirst)
        children.forEach { child -> yieldAll(child.elementsDepthFirst()) }
    }

    private fun PsiField.symbolIdentity() = "${containingClass?.qualifiedName}#$name"

    private fun PsiMethod.symbolIdentity() = "${containingClass?.qualifiedName}#$name(${parameterList.parameters.joinToString { parameter ->
        parameter.type.canonicalText
    }})"

    private fun PsiMember.matchesNamespace(namespace: String): Boolean {
        val className = containingClass?.qualifiedName ?: return false
        return when (namespace) {
            FRAMEWORK_NAMESPACE -> className.startsWith(FRAMEWORK_PACKAGE_PREFIX)
            APP_NAMESPACE -> !className.startsWith(FRAMEWORK_PACKAGE_PREFIX)
            else -> className.startsWith("$namespace.")
        }
    }

    private fun PsiClass.documentedSetterProofs(attributeName: String, namespace: String) =
        generateSequence(this) { current -> current.superClass }
            .flatMap { current -> current.methods.asSequence() }
            .flatMap { method -> method.documentedSetterProofs(attributeName, namespace, this).asSequence() }
            .toList()

    private fun PsiMethod.documentedSetterProofs(
        attributeName: String,
        namespace: String,
        targetClass: PsiClass
    ): List<MemberProof> {
        if (!hasModifierProperty(PsiModifier.PUBLIC) || hasModifierProperty(PsiModifier.STATIC) ||
            isConstructor || !referencesAttribute(attributeName, namespace)
        ) return emptyList()

        val parameters = parameterList.parameters
        val sourceParameters = (navigationElement as? PsiMethod)?.parameterList?.parameters.orEmpty()
        if (PropertyUtilBase.isSimplePropertySetter(this)) return listOf(
            documentedSetterProof(parameters.single(), sourceParameters.singleOrNull())
        )
        if (!isBoundedMultiParameterSetter()) return emptyList()

        return parameters.mapIndexedNotNull { valueIndex, parameter ->
            val sourceParameter = sourceParameters.getOrNull(valueIndex)
            val integerMetadata = integerMetadata(null, this, parameter, sourceParameter)
            val resourceTypes = parameter.resourceTypes(sourceParameter)
            if (resourceTypes == null && integerMetadata.fields.isEmpty()) return@mapIndexedNotNull null

            val fixedArguments = parameters.indices
                .filter { index -> index != valueIndex }
                .associateWith { index ->
                    val getter = targetClass.stateGetter(this, parameters[index]) ?: return@mapIndexedNotNull null
                    val returnType = getter.returnType ?: return@mapIndexedNotNull null
                    val propertyName = PropertyUtilBase.getPropertyNameByGetter(getter)
                    Value.ReceiverProperty(getter.kotlinPropertyName(propertyName, returnType))
                }
            MemberProof(
                memberName = name,
                memberKind = MemberKind.METHOD,
                targetType = parameter.type,
                valueKind = if (resourceTypes == null) ValueKind.TARGET_TYPE else ValueKind.RESOURCE_ID,
                valueArgumentIndex = valueIndex,
                fixedArguments = fixedArguments,
                resourceTypes = resourceTypes,
                integerMetadata = integerMetadata,
                requiresExactIntegerNames = resourceTypes == null
            )
        }
    }

    private fun PsiMethod.documentedSetterProof(
        parameter: PsiParameter,
        sourceParameter: PsiParameter?
    ): MemberProof {
        val resourceTypes = parameter.resourceTypes(sourceParameter)
        return MemberProof(
            memberName = name,
            memberKind = MemberKind.METHOD,
            targetType = parameter.type,
            valueKind = if (resourceTypes == null) ValueKind.TARGET_TYPE else ValueKind.RESOURCE_ID,
            resourceTypes = resourceTypes,
            integerMetadata = integerMetadata(null, this, parameter, sourceParameter)
        )
    }

    private fun PsiMethod.isBoundedMultiParameterSetter() = name.length > 3 && name.startsWith("set") &&
        name[3].isUpperCase() && returnType == PsiTypes.voidType() && parameterList.parametersCount == 2

    private fun PsiClass.stateGetter(setter: PsiMethod, parameter: PsiParameter): PsiMethod? {
        val propertySuffix = setter.name.removePrefix("set")
        val getterNames = setOf("get$propertySuffix", "is$propertySuffix")

        return allMethods.asSequence()
            .filter { method ->
                method.name in getterNames && method.hasModifierProperty(PsiModifier.PUBLIC) &&
                    !method.hasModifierProperty(PsiModifier.STATIC) &&
                    PropertyUtilBase.isSimplePropertyGetter(method) && method.returnType?.let { returnType ->
                        TypeConversionUtil.isAssignable(parameter.type, returnType)
                    } == true
            }
            .distinctBy { method -> method.symbolIdentity() }
            .singleOrNull()
    }

    private fun PsiMethod.referencesAttribute(attributeName: String, namespace: String) =
        sequenceOf(this, navigationElement as? PsiMethod)
            .filterNotNull()
            .distinct()
            .mapNotNull(PsiMethod::getDocComment)
            .flatMap { comment -> DOCUMENTED_ATTRIBUTE.findAll(comment.text) }
            .any { match ->
                val resourcePackage = match.groupValues[1]
                val documentedAttribute = match.groupValues[3]
                val matchesAttribute = when (match.groupValues[2]) {
                    "styleable" -> documentedAttribute.matchesStyleableAttributeName(
                        attributeName,
                        namespace,
                        resourcePackage
                    )
                    "attr" -> documentedAttribute == attributeName
                    else -> false
                }
                matchesAttribute && when (match.groupValues[2]) {
                    "styleable" -> true
                    else -> when (namespace) {
                        FRAMEWORK_NAMESPACE -> resourcePackage == FRAMEWORK_NAMESPACE
                        APP_NAMESPACE -> resourcePackage != FRAMEWORK_NAMESPACE
                        else -> resourcePackage == namespace
                    }
                }
            }

    private fun PsiParameter.resourceTypes(sourceParameter: PsiParameter?) = DRAWABLE_RESOURCE_ANNOTATIONS
        .takeIf { annotations ->
            sequenceOf(
                this,
                navigationElement as? PsiModifierListOwner,
                sourceParameter,
                sourceParameter?.navigationElement as? PsiModifierListOwner
            ).filterNotNull().distinct()
                .flatMap { owner -> owner.annotations.asSequence() }
                .any { annotation -> annotations.any { qualifiedName -> annotation.matchesAnnotation(qualifiedName) } }
        }?.let { setOf("drawable", "mipmap") }

    private fun PsiClass.typedArrayProofs(attributeName: String, namespace: String): List<MemberProof> {
        val targetClass = this
        return generateSequence(this) { current -> current.superClass }
            .flatMap { current -> current.constructors.asSequence() }
            .mapNotNull { constructor -> constructor.sourceUMethod() }
            .flatMap { constructor -> constructor.directTypedArrayProofs(attributeName, namespace, targetClass).asSequence() }
            .toList()
    }

    private fun PsiMethod.sourceUMethod() = (navigationElement.toUElementOfType<UMethod>()
        ?: toUElementOfType<UMethod>())?.takeIf { method -> method.uastBody != null }

    private fun UMethod.directTypedArrayProofs(
        attributeName: String,
        namespace: String,
        targetClass: PsiClass
    ): List<MemberProof> {
        val proofs = mutableListOf<MemberProof>()
        uastBody?.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val valueKind = node.typedArrayValueKind(attributeName, namespace) ?: return super.visitCallExpression(node)
                val sourceType = node.returnType ?: node.resolve()?.returnType ?: return super.visitCallExpression(node)

                val proof = node.directTargetProof(targetClass, valueKind, sourceType)
                    ?: node.localTargetProof(this@directTypedArrayProofs, targetClass, valueKind, sourceType)
                proof?.let(proofs::add)

                return super.visitCallExpression(node)
            }
        })
        return proofs
    }

    private fun UCallExpression.typedArrayValueKind(attributeName: String, namespace: String): ValueKind? {
        val method = resolve() ?: return null
        val valueKind = method.provenTypedArrayValueKind() ?: return null
        val styleableField = valueArguments.firstOrNull()?.resolvedField() ?: return null
        if (!styleableField.matchesStyleableAttribute(attributeName, namespace)) return null

        return valueKind
    }

    private fun PsiMethod.provenTypedArrayValueKind(): ValueKind? {
        val valueKind = typedArrayValueKind(name) ?: return null
        if (containingClass?.qualifiedName == TYPED_ARRAY_CLASS) return valueKind

        val sourceMethod = sourceUMethod() ?: return null
        val indexParameter = sourceMethod.javaPsi.parameterList.parameters.firstOrNull() ?: return null

        val delegatedKinds = mutableListOf<ValueKind>()
        sourceMethod.uastBody?.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val delegatedMethod = node.resolve() ?: return super.visitCallExpression(node)
                val delegatedKind = typedArrayValueKind(delegatedMethod.name)
                if (delegatedKind == valueKind && delegatedMethod.containingClass?.qualifiedName == TYPED_ARRAY_CLASS &&
                    node.valueArguments.firstOrNull()?.resolvesTo(indexParameter) == true && node.isDirectReturn()
                ) delegatedKinds += delegatedKind

                return super.visitCallExpression(node)
            }
        })

        return delegatedKinds.distinct().singleOrNull()
    }

    private fun UExpression.resolvesTo(element: PsiElement) = (this as? UResolvable)?.resolve() == element ||
        sourcePsi?.reference?.resolve() == element

    private fun UCallExpression.isDirectReturn() = transparentQualifiedExpression().uastParent is UReturnExpression

    private fun UExpression.resolvedField() = ((this as? UResolvable)?.resolve() as? PsiField)
        ?: (sourcePsi?.reference?.resolve() as? PsiField)

    private fun PsiField.matchesStyleableAttribute(attributeName: String, namespace: String): Boolean {
        val styleableClassName = containingClass?.qualifiedName ?: return false
        val resourcePackage = styleableClassName.substringBefore(".R.styleable", missingDelimiterValue = "")

        return resourcePackage.isNotEmpty() && name.matchesStyleableAttributeName(attributeName, namespace, resourcePackage)
    }

    private fun String.matchesStyleableAttributeName(
        attributeName: String,
        namespace: String,
        resourcePackage: String
    ): Boolean {
        if (!endsWith("_$attributeName")) return false
        val isEmbeddedFrameworkAttribute = endsWith("_android_$attributeName")

        return when (namespace) {
            FRAMEWORK_NAMESPACE -> resourcePackage == FRAMEWORK_NAMESPACE || isEmbeddedFrameworkAttribute
            APP_NAMESPACE -> resourcePackage != FRAMEWORK_NAMESPACE && !isEmbeddedFrameworkAttribute
            else -> resourcePackage == namespace && !isEmbeddedFrameworkAttribute
        }
    }

    private fun UExpression.directTargetProof(
        targetClass: PsiClass,
        valueKind: ValueKind,
        sourceType: PsiType
    ): MemberProof? {
        val expression = transparentQualifiedExpression()
        val targetCall = expression.uastParent as? UCallExpression ?: return null
        val argumentIndex = targetCall.valueArguments.indexOfFirst { argument -> argument === expression }
        if (argumentIndex < 0) return null

        val method = targetCall.resolve() ?: return null
        if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.hasModifierProperty(PsiModifier.STATIC) &&
            !method.isConstructor && method.parameterList.parametersCount == 1 &&
            targetClass.containsMember(method) && targetCall.hasThisReceiver()
        ) {
            val targetType = method.parameterList.parameters.single().type
            if (!TypeConversionUtil.isAssignable(targetType, sourceType)) return null

            return MemberProof(
                method.name,
                MemberKind.METHOD,
                targetType,
                valueKind,
                integerMetadata = integerMetadata(
                    null,
                    method,
                    method.parameterList.parameters.single(),
                    (method.navigationElement as? PsiMethod)?.parameterList?.parameters?.singleOrNull()
                )
            )
        }

        return targetClass.delegatingSetterProof(targetCall, method, argumentIndex, valueKind, sourceType)
    }

    private fun PsiClass.delegatingSetterProof(
        targetCall: UCallExpression,
        delegatedMethod: PsiMethod,
        argumentIndex: Int,
        valueKind: ValueKind,
        sourceType: PsiType
    ): MemberProof? {
        val receiverField = targetCall.receiver?.resolvedField() ?: return null
        val setter = generateSequence(this) { current -> current.superClass }
            .flatMap { current -> current.methods.asSequence() }
            .filter { method ->
                method.hasModifierProperty(PsiModifier.PUBLIC) &&
                    !method.hasModifierProperty(PsiModifier.STATIC) &&
                    !method.isConstructor && PropertyUtilBase.isSimplePropertySetter(method) &&
                    method.parameterList.parametersCount == 1 &&
                    TypeConversionUtil.isAssignable(method.parameterList.parameters.single().type, sourceType)
            }
            .filter { method -> method.directlyDelegatesTo(delegatedMethod, receiverField, argumentIndex) }
            .distinctBy { method -> method.symbolIdentity() }
            .singleOrNull() ?: return null

        val parameter = setter.parameterList.parameters.single()
        return MemberProof(
            setter.name,
            MemberKind.METHOD,
            parameter.type,
            valueKind,
            integerMetadata = integerMetadata(
                null,
                setter,
                parameter,
                (setter.navigationElement as? PsiMethod)?.parameterList?.parameters?.singleOrNull()
            )
        )
    }

    private fun PsiMethod.directlyDelegatesTo(
        delegatedMethod: PsiMethod,
        receiverField: PsiField,
        argumentIndex: Int
    ): Boolean {
        val sourceMethod = sourceUMethod() ?: return false
        val sourceParameter = sourceMethod.javaPsi.parameterList.parameters.singleOrNull() ?: return false
        var parameterUsages = 0
        var matchingCalls = 0

        sourceMethod.uastBody?.accept(object : AbstractUastVisitor() {

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                if (node.resolve() == sourceParameter) parameterUsages++
                return super.visitSimpleNameReferenceExpression(node)
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                val method = node.resolve()
                val argument = node.valueArguments.getOrNull(argumentIndex)
                if (method?.symbolIdentity() == delegatedMethod.symbolIdentity() &&
                    node.receiver?.resolvedField()?.symbolIdentity() == receiverField.symbolIdentity() &&
                    argument?.resolvesTo(sourceParameter) == true
                ) matchingCalls++

                return super.visitCallExpression(node)
            }
        })

        return parameterUsages == 1 && matchingCalls == 1
    }

    private fun UCallExpression.localTargetProof(
        method: UMethod,
        targetClass: PsiClass,
        valueKind: ValueKind,
        sourceType: PsiType
    ): MemberProof? {
        val expression = transparentQualifiedExpression()
        val variable = expression.uastParent as? UVariable ?: return null
        if (variable.uastInitializer !== expression) return null

        val declaration = variable.sourcePsi ?: variable
        val proofUsages = mutableListOf<Pair<MemberProof, UReferenceExpression>>()
        val nullGuards = mutableListOf<NullGuard>()
        var hasUnsupportedUsage = false

        method.uastBody?.accept(object : AbstractUastVisitor() {
            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                if (node.resolve() != declaration) return super.visitSimpleNameReferenceExpression(node)
                node.nullGuard()?.let { guard ->
                    nullGuards += guard
                    return super.visitSimpleNameReferenceExpression(node)
                }
                val proof = node.directTargetProof(targetClass, valueKind, sourceType)
                if (proof == null) hasUnsupportedUsage = true else proofUsages += proof to node

                return super.visitSimpleNameReferenceExpression(node)
            }
        })

        if (hasUnsupportedUsage || proofUsages.size != 1) return null
        val proof = proofUsages.single()
        if (nullGuards.isEmpty()) return proof.first
        val guard = nullGuards.singleOrNull() ?: return null

        if (!proof.second.isInside(guard.nonNullBranch)) return null
        return proof.first.copy(rejectsNullValue = true)
    }

    private fun UExpression.transparentQualifiedExpression(): UExpression {
        var expression = this
        while (expression.uastParent is UQualifiedReferenceExpression) {
            val parent = expression.uastParent as UQualifiedReferenceExpression
            if (parent.selector !== expression) break
            expression = parent
        }
        return expression
    }

    private fun UReferenceExpression.nullGuard(): NullGuard? {
        val expression = transparentQualifiedExpression()
        val binary = expression.uastParent as? UBinaryExpression ?: return null
        if (binary.operator !in setOf(
                UastBinaryOperator.EQUALS,
                UastBinaryOperator.NOT_EQUALS,
                UastBinaryOperator.IDENTITY_EQUALS,
                UastBinaryOperator.IDENTITY_NOT_EQUALS
            )
        ) return null

        val other = when (expression) {
            binary.leftOperand -> binary.rightOperand
            binary.rightOperand -> binary.leftOperand
            else -> return null
        }
        if (other !is ULiteralExpression || other.value != null) return null
        val conditional = binary.uastParent as? UIfExpression ?: return null
        if (conditional.condition !== binary) return null

        val nonNullBranch = when (binary.operator) {
            UastBinaryOperator.NOT_EQUALS,
            UastBinaryOperator.IDENTITY_NOT_EQUALS -> conditional.thenExpression
            else -> conditional.elseExpression
        } ?: return null
        return NullGuard(nonNullBranch)
    }

    private fun UExpression.isInside(ancestor: UExpression): Boolean {
        var current: UElement? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.uastParent
        }
        return false
    }

    private fun PsiClass.containsMember(method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        return this == owner || isInheritor(owner, true)
    }

    private fun UCallExpression.hasThisReceiver() = receiver == null || receiver is UThisExpression

    private fun ValueKind.convert(
        rawValue: String,
        targetType: PsiType,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?,
        resourceTypes: Set<String>?,
        integerMetadata: IntegerMetadata,
        themeAttributeDefinition: AndroidAttributeResolver.Attribute?
    ): Value? {
        if (rawValue == "@null" && targetType !is PsiPrimitiveType) return Value.Null
        themeAttributeDefinition?.let { themeDefinition ->
            rawValue.themeAttributeValue(
                valueKind = this,
                targetType = targetType,
                targetDefinition = definition,
                themeDefinition = themeDefinition,
                currentModuleResourcePackageName = resourcePackageName,
                resourceTypes = resourceTypes
            )?.let { value -> return value }
        }
        return when (this) {
            ValueKind.TARGET_TYPE -> convertByTargetType(
                rawValue,
                targetType,
                definition,
                resourcePackageName,
                integerMetadata
            )
            ValueKind.TEXT -> rawValue.literalText(targetType, resourcePackageName)
            ValueKind.BOOLEAN -> rawValue.literalBoolean(targetType, resourcePackageName)
            ValueKind.INTEGER -> rawValue.literalInteger(targetType, definition, resourcePackageName, integerMetadata)
            ValueKind.FLOAT -> rawValue.literalFloatingPoint(targetType, resourcePackageName)
            ValueKind.DIMENSION_PIXEL_OFFSET -> rawValue.literalDimensionPixel(
                targetType,
                resourcePackageName,
                "dimenPixelOffsetResource"
            )
            ValueKind.DIMENSION_PIXEL_SIZE -> rawValue.literalDimensionPixel(
                targetType,
                resourcePackageName,
                "dimenPixelSizeResource"
            )
            ValueKind.DRAWABLE -> rawValue.literalDrawable(targetType, definition, resourcePackageName)
            ValueKind.COLOR -> rawValue.literalColor(targetType, resourcePackageName)
            ValueKind.COLOR_STATE_LIST -> rawValue.literalStateColor(targetType, resourcePackageName)
            ValueKind.RESOURCE_ID -> rawValue.literalResourceId(targetType, resourcePackageName, resourceTypes)
        }
    }

    private fun String.themeAttributeValue(
        valueKind: ValueKind,
        targetType: PsiType,
        targetDefinition: AndroidAttributeResolver.Attribute?,
        themeDefinition: AndroidAttributeResolver.Attribute,
        currentModuleResourcePackageName: String?,
        resourceTypes: Set<String>?
    ): Value.ThemeAttribute? {
        val resultType = valueKind.themeAttributeResultType(
            targetType = targetType,
            targetFormats = targetDefinition?.formats.orEmpty(),
            themeFormats = themeDefinition.formats,
            resourceTypes = resourceTypes
        ) ?: return null

        return ThemeAttributePlanner.plan(
            rawValue = this,
            definition = themeDefinition,
            currentModuleResourcePackageName = currentModuleResourcePackageName,
            resultType = resultType
        )
    }

    private fun ValueKind.themeAttributeResultType(
        targetType: PsiType,
        targetFormats: Set<AttributeFormat>,
        themeFormats: Set<AttributeFormat>,
        resourceTypes: Set<String>?
    ) = when (this) {
        ValueKind.TARGET_TYPE -> targetType.themeAttributeResultType(targetFormats, themeFormats)
        ValueKind.TEXT -> ThemeAttributePlanner.ResultType.STRING.takeIf {
            targetType.isTextType() && AttributeFormat.STRING in themeFormats
        }
        ValueKind.BOOLEAN -> ThemeAttributePlanner.ResultType.BOOLEAN.takeIf {
            targetType.isBooleanType() && AttributeFormat.BOOLEAN in themeFormats
        }
        ValueKind.INTEGER -> ThemeAttributePlanner.ResultType.INTEGER.takeIf {
            targetType.isIntType() && themeFormats.hasIntegerFormat()
        }
        ValueKind.FLOAT -> targetType.floatingPointThemeAttributeResultType(targetFormats, themeFormats)
        ValueKind.DIMENSION_PIXEL_OFFSET,
        ValueKind.DIMENSION_PIXEL_SIZE -> null
        ValueKind.DRAWABLE -> ThemeAttributePlanner.ResultType.DRAWABLE.takeIf {
            targetType.isDrawableType() && themeFormats.any { format ->
                format == AttributeFormat.REFERENCE || format == AttributeFormat.COLOR
            }
        }
        ValueKind.COLOR -> ThemeAttributePlanner.ResultType.COLOR.takeIf {
            targetType.isIntType() && AttributeFormat.COLOR in themeFormats
        }
        ValueKind.COLOR_STATE_LIST -> ThemeAttributePlanner.ResultType.COLOR_STATE_LIST.takeIf {
            targetType.isColorStateListType() && themeFormats.any { format ->
                format == AttributeFormat.REFERENCE || format == AttributeFormat.COLOR
            }
        }
        ValueKind.RESOURCE_ID -> ThemeAttributePlanner.ResultType.RESOURCE_ID.takeIf {
            targetType.isIntType() && !resourceTypes.isNullOrEmpty() && AttributeFormat.REFERENCE in themeFormats
        }
    }

    private fun PsiType.themeAttributeResultType(
        targetFormats: Set<AttributeFormat>,
        themeFormats: Set<AttributeFormat>
    ) = when {
        isTextType() && AttributeFormat.STRING in themeFormats -> ThemeAttributePlanner.ResultType.STRING
        isBooleanType() && AttributeFormat.BOOLEAN in themeFormats -> ThemeAttributePlanner.ResultType.BOOLEAN
        isFloatType() -> floatingPointThemeAttributeResultType(targetFormats, themeFormats)
        isIntType() -> buildList {
            if (AttributeFormat.COLOR in themeFormats &&
                (targetFormats.isEmpty() || AttributeFormat.COLOR in targetFormats)
            ) add(ThemeAttributePlanner.ResultType.COLOR)
            if (themeFormats.hasIntegerFormat() &&
                (targetFormats.isEmpty() || targetFormats.hasIntegerFormat())
            ) add(ThemeAttributePlanner.ResultType.INTEGER)
        }.distinct().singleOrNull()
        isDrawableType() && themeFormats.any { format ->
            format == AttributeFormat.REFERENCE || format == AttributeFormat.COLOR
        } -> ThemeAttributePlanner.ResultType.DRAWABLE
        isColorStateListType() && themeFormats.any { format ->
            format == AttributeFormat.REFERENCE || format == AttributeFormat.COLOR
        } -> ThemeAttributePlanner.ResultType.COLOR_STATE_LIST
        else -> null
    }

    private fun PsiType.floatingPointThemeAttributeResultType(
        targetFormats: Set<AttributeFormat>,
        themeFormats: Set<AttributeFormat>
    ): ThemeAttributePlanner.ResultType? {
        if (!isFloatType()) return null
        return buildList {
            if (AttributeFormat.DIMENSION in themeFormats &&
                (targetFormats.isEmpty() || AttributeFormat.DIMENSION in targetFormats)
            ) add(ThemeAttributePlanner.ResultType.DIMENSION)
            if (AttributeFormat.FLOAT in themeFormats &&
                (targetFormats.isEmpty() || AttributeFormat.FLOAT in targetFormats)
            ) add(ThemeAttributePlanner.ResultType.FLOAT)
        }.distinct().singleOrNull()
    }

    private fun Set<AttributeFormat>.hasIntegerFormat() = any { format ->
        format == AttributeFormat.INTEGER || format == AttributeFormat.ENUM || format == AttributeFormat.FLAGS
    }

    private fun convertByTargetType(
        rawValue: String,
        targetType: PsiType,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?,
        integerMetadata: IntegerMetadata
    ) = when {
        targetType.isTextType() -> rawValue.literalText(targetType, resourcePackageName)
        targetType.isBooleanType() -> rawValue.literalBoolean(targetType, resourcePackageName)
        targetType is PsiClassType && targetType.resolve()?.isEnum == true -> rawValue.literalEnum(targetType, definition)
        targetType.isIntegerType() -> rawValue.literalTargetInteger(targetType, definition, resourcePackageName, integerMetadata)
        targetType.isFloatingPointType() -> rawValue.literalFloatingPoint(targetType, resourcePackageName)
        targetType.isDrawableType() -> rawValue.literalDrawable(targetType, definition, resourcePackageName)
        targetType.isColorStateListType() -> rawValue.literalStateColor(targetType, resourcePackageName)
        else -> null
    }

    private fun String.literalEnum(
        targetType: PsiClassType,
        definition: AndroidAttributeResolver.Attribute?
    ): Value.SymbolicConstant? {
        if (isResourceLike()) return null

        val attribute = definition?.takeIf { resolved -> AttributeFormat.ENUM in resolved.formats } ?: return null
        val mappedValue = attribute.definition.getValueMapping(this)?.toLong() ?: return null
        val definitionValues = attribute.values.map { value ->
            attribute.definition.getValueMapping(value)?.toLong() ?: return null
        }
        if (definitionValues.isEmpty() || definitionValues.distinct().size != definitionValues.size) return null
        val enumClass = targetType.resolve() ?: return null
        val constants = sequenceOf(enumClass, enumClass.navigationElement as? PsiClass)
            .filterNotNull()
            .distinct()
            .firstNotNullOfOrNull { type -> type.numericEnumConstants() } ?: return null

        if (constants.map(Pair<Long, PsiEnumConstant>::first).toSet() != definitionValues.toSet()) return null
        return constants.singleOrNull { (value) -> value == mappedValue }?.second?.toSymbolicConstant()
    }

    private fun PsiClass.numericEnumConstants(): List<Pair<Long, PsiEnumConstant>>? {
        if (!isEnum) return null
        val constants = fields.filterIsInstance<PsiEnumConstant>()
        if (constants.isEmpty()) return null

        val evaluator = JavaPsiFacade.getInstance(project).constantEvaluationHelper

        return constants.map { constant ->
            val argument = constant.argumentList?.expressions?.singleOrNull() ?: return null
            val value = evaluator.computeConstantExpression(argument) as? Number ?: return null
            value.toLong() to constant
        }.takeIf { values -> values.map(Pair<Long, PsiEnumConstant>::first).distinct().size == values.size }
    }

    private fun String.literalText(targetType: PsiType, resourcePackageName: String?): Value? {
        if (!targetType.isTextType()) return null
        val resource = localResource(resourcePackageName)
        if (resource != null) return resource.takeIf { reference -> reference.type == "string" }
            ?.toValue(if (targetType.isStringType()) "stringResource" else "textResource")
        return takeUnless { value -> value.isResourceLike() }?.let(Value::Text)
    }

    private fun String.literalBoolean(targetType: PsiType, resourcePackageName: String?): Value? {
        if (!targetType.isBooleanType()) return null
        val resource = localResource(resourcePackageName)
        if (resource != null) return resource.takeIf { reference -> reference.type == "bool" }?.toValue("booleanResource")

        return takeUnless { value -> value.isResourceLike() }
            ?.toBooleanStrictOrNull()
            ?.let(Value::BooleanLiteral)
    }

    private fun String.literalInteger(
        targetType: PsiType,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?,
        integerMetadata: IntegerMetadata
    ): Value? {
        if (!targetType.isIntegerType()) return null

        val resource = localResource(resourcePackageName)
        if (resource != null) return resource.takeIf { reference -> reference.type == "integer" }
            ?.toValue("integerResource")
        if (isResourceLike()) return null

        val literal = takeIf { value -> INTEGER_VALUE.matches(value) }?.toLongLiteralOrNull()
        if (literal != null) return Value.IntegerLiteral(literal)

        val formats = definition?.formats.orEmpty()
        val tokens = split('|').map(String::trim).filter(String::isNotEmpty)
        if (tokens.isEmpty() || tokens.size > 1 && AttributeFormat.FLAGS !in formats) return null
        val mappings = definition?.let { attribute ->
            tokens.mapNotNull(attribute.definition::getValueMapping).takeIf { values -> values.size == tokens.size }
        }
        integerMetadata.symbolicValue(tokens, mappings)?.let { value -> return value }
        if (AttributeFormat.ENUM !in formats && AttributeFormat.FLAGS !in formats) return null
        mappings ?: return null

        return Value.IntegerLiteral(mappings.fold(0) { result, value -> result or value }.toLong())
    }

    private fun String.literalTargetInteger(
        targetType: PsiType,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?,
        integerMetadata: IntegerMetadata
    ): Value? {
        val resource = localResource(resourcePackageName)
        if (resource?.type == "color" && AttributeFormat.COLOR in definition?.formats.orEmpty()) return resource.toValue("colorResource")

        return literalInteger(targetType, definition, resourcePackageName, integerMetadata)
    }

    private fun String.literalFloatingPoint(targetType: PsiType, resourcePackageName: String?): Value? {
        if (!targetType.isFloatingPointType()) return null
        val resource = localResource(resourcePackageName)
        if (resource != null) return resource.takeIf { reference -> reference.type == "dimen" }?.toValue("dimenResource")
        if (isResourceLike() || !FLOAT_VALUE.matches(this)) return null

        return Value.FloatingPointLiteral(this, targetType == PsiTypes.floatType())
    }

    private fun String.literalDimensionPixel(
        targetType: PsiType,
        resourcePackageName: String?,
        resourceHelperName: String
    ): Value? {
        if (!targetType.isIntegerType()) return null

        val resource = localResource(resourcePackageName)
        if (resource != null) return resource.takeIf { reference -> reference.type == "dimen" }?.toValue(resourceHelperName)
        if (isResourceLike()) return null

        DP_VALUE.matchEntire(this)?.groupValues?.get(1)?.toIntOrNull()?.let { value -> return Value.Dp(value) }
        PX_VALUE.matchEntire(this)?.groupValues?.get(1)?.toLongOrNull()?.let { value -> return Value.IntegerLiteral(value) }

        return null
    }

    private fun String.literalDrawable(
        targetType: PsiType,
        definition: AndroidAttributeResolver.Attribute?,
        resourcePackageName: String?
    ): Value? {
        if (!targetType.isDrawableType()) return null

        val resource = localResource(resourcePackageName) ?: return null
        if (resource.type in setOf("drawable", "mipmap")) return resource.toValue("drawableResource")
        if (resource.type != "color" || AttributeFormat.COLOR !in definition?.formats.orEmpty()) return null

        return Value.ExtensionCall(
            receiver = resource.toValue("colorResource"),
            importName = ANDROIDX_COLOR_TO_DRAWABLE,
            functionName = "toDrawable"
        )
    }

    private fun String.literalColor(targetType: PsiType, resourcePackageName: String?): Value? {
        if (!targetType.isIntegerType()) return null
        return localResource(resourcePackageName)
            ?.takeIf { reference -> reference.type == "color" }
            ?.toValue("colorResource")
    }

    private fun String.literalStateColor(targetType: PsiType, resourcePackageName: String?): Value? {
        if (!targetType.isColorStateListType()) return null
        return localResource(resourcePackageName)
            ?.takeIf { reference -> reference.type == "color" }
            ?.toValue("stateColorResource")
    }

    private fun String.literalResourceId(
        targetType: PsiType,
        resourcePackageName: String?,
        resourceTypes: Set<String>?
    ): Value? {
        if (!targetType.isIntegerType()) return null
        return localResource(resourcePackageName)
            ?.takeIf { reference -> reference.type != "id" && (resourceTypes == null || reference.type in resourceTypes) }
            ?.toValue(helperName = null)
    }

    private fun String.localResource(resourcePackageName: String?): LocalResource? {
        val match = RESOURCE_VALUE.matchEntire(this) ?: return null
        val declaredPackage = match.groupValues[1]
        val packageName = when {
            declaredPackage == FRAMEWORK_NAMESPACE -> FRAMEWORK_NAMESPACE
            declaredPackage.isNotEmpty() -> return null
            else -> resourcePackageName?.takeIf(String::isNotBlank) ?: return null
        }
        return LocalResource(packageName, match.groupValues[2], match.groupValues[3])
    }

    private fun String.toLongLiteralOrNull() = when {
        startsWith("-0x", true) -> substring(3).toLongOrNull(16)?.let(Long::unaryMinus)
        startsWith("+0x", true) -> substring(3).toLongOrNull(16)
        startsWith("0x", true) -> substring(2).toLongOrNull(16)
        else -> toLongOrNull()
    }

    private fun String.isResourceLike() = startsWith('@') || startsWith('?')

    private fun PsiType.isTextType() = canonicalText in setOf(
        "String", "CharSequence", "java.lang.String", "java.lang.CharSequence"
    )

    private fun PsiType.isStringType() = canonicalText in setOf("String", "java.lang.String")

    private fun PsiType.isBooleanType() = this == PsiTypes.booleanType() || canonicalText in setOf(
        "Boolean", "java.lang.Boolean"
    )

    private fun PsiType.isIntegerType() = this in setOf(
        PsiTypes.byteType(),
        PsiTypes.shortType(),
        PsiTypes.intType(),
        PsiTypes.longType()
    ) || canonicalText in setOf(
        "Byte", "Short",
        "Integer", "Long",
        "java.lang.Byte", "java.lang.Short",
        "java.lang.Integer", "java.lang.Long"
    )

    private fun PsiType.isIntType() = this == PsiTypes.intType() || canonicalText in setOf(
        "Int", "Integer", "java.lang.Integer"
    )

    private fun PsiType.isFloatingPointType() = this == PsiTypes.floatType() || this == PsiTypes.doubleType() ||
        canonicalText in setOf("Float", "Double", "java.lang.Float", "java.lang.Double")

    private fun PsiType.isFloatType() = this == PsiTypes.floatType() || canonicalText in setOf(
        "Float", "java.lang.Float"
    )

    private fun PsiType.isDrawableType() = canonicalText == "android.graphics.drawable.Drawable"
    private fun PsiType.isColorStateListType() = canonicalText == "android.content.res.ColorStateList"

    private data class MemberProof(
        val memberName: String,
        val memberKind: MemberKind,
        val targetType: PsiType,
        val valueKind: ValueKind,
        val rejectsNullValue: Boolean = false,
        val valueArgumentIndex: Int = 0,
        val fixedArguments: Map<Int, Value> = emptyMap(),
        val resourceTypes: Set<String>? = null,
        val integerMetadata: IntegerMetadata = IntegerMetadata(emptyList(), emptyMap()),
        val requiresExactIntegerNames: Boolean = false
    ) {

        val argumentCount get() = fixedArguments.size + 1

        val identity get() = listOf(
            memberName,
            memberKind.name,
            targetType.canonicalText,
            valueKind.name,
            rejectsNullValue,
            valueArgumentIndex,
            fixedArguments.entries.sortedBy(Map.Entry<Int, Value>::key).joinToString("|") { (index, value) ->
                "$index=$value"
            },
            resourceTypes?.sorted().orEmpty().joinToString("|"),
            integerMetadata.fields.map { field -> field.symbolIdentity() }.sorted().joinToString("|"),
            integerMetadata.requiresAttributeValueMapping,
            requiresExactIntegerNames
        ).joinToString("|")
    }

    private data class AnnotatedAttributeProof(
        val name: String,
        val namespace: String?,
        val annotation: PsiAnnotation
    )

    private data class IntegerMetadata(
        val fields: List<PsiField>,
        val namedFields: Map<String, List<PsiField>>,
        val requiresAttributeValueMapping: Boolean = false
    ) {

        fun matchesDefinitionValues(definition: AndroidAttributeResolver.Attribute?): Boolean {
            val attribute = definition ?: return false

            return attribute.values.isNotEmpty() && attribute.values.all { value ->
                val mappedValue = attribute.definition.getValueMapping(value)?.toLong() ?: return false
                val matchingFields = namedFields[value] ?: fields.filter { field ->
                    field.name.normalizedContractName() == value.normalizedContractName()
                }
                matchingFields.toSymbolicConstants(mappedValue) != null
            }
        }

        fun symbolicValue(tokens: List<String>, mappings: List<Int>?): Value? {
            if (requiresAttributeValueMapping && mappings == null) return null

            val constants = tokens.flatMapIndexed { index, token ->
                val mappedValue = mappings?.getOrNull(index)?.toLong()
                namedFields[token]?.toSymbolicConstants(mappedValue)
                    ?: mappedValue?.let { value ->
                        fields.asSequence()
                            .filter { field -> field.isPublicIntegerConstant() }
                            .filter { field -> (field.computeConstantValue() as? Number)?.toLong() == value }
                            .mapNotNull { field -> field.toSymbolicConstant() }
                            .distinct()
                            .singleOrNull()
                            ?.let(::listOf)
                    }
                    ?: return null
            }.distinct()
            return constants.singleOrNull() ?: Value.BitwiseOr(constants)
        }
    }

    private fun String.normalizedContractName() = filter(Char::isLetterOrDigit).lowercase()

    private fun List<PsiField>.toSymbolicConstants(mappedValue: Long?): List<Value.SymbolicConstant>? {
        val uniqueFields = distinctBy { field -> field.symbolIdentity() }
        val constants = uniqueFields.mapNotNull { field ->
            val value = (field.computeConstantValue() as? Number)?.toLong()
            val symbol = field.takeIf { candidate -> candidate.isPublicIntegerConstant() }?.toSymbolicConstant()
            if (value == null || symbol == null) null else value to symbol
        }
        if (constants.size != uniqueFields.size) return null

        if (mappedValue != null && constants.fold(0L) { value, constant -> value or constant.first } != mappedValue) return null
        return constants.map { constant -> constant.second }
    }

    private fun PsiField.isPublicIntegerConstant() = hasModifierProperty(PsiModifier.PUBLIC) &&
        hasModifierProperty(PsiModifier.STATIC) && hasModifierProperty(PsiModifier.FINAL) && type.isIntegerType()

    private fun PsiField.toSymbolicConstant(): Value.SymbolicConstant? {
        val classes = generateSequence(containingClass) { type -> type.containingClass }
            .toList()
            .asReversed()
        if (classes.isEmpty() || classes.any { type -> !type.hasModifierProperty(PsiModifier.PUBLIC) }) return null
        val importName = classes.first().qualifiedName ?: return null
        val qualifier = classes.mapNotNull(PsiClass::getName).joinToString(".").takeIf(String::isNotBlank) ?: return null

        return Value.SymbolicConstant(importName, qualifier, name)
    }

    private data class NullGuard(val nonNullBranch: UExpression)

    private data class LocalResource(val packageName: String, val type: String, val name: String) {

        val resourceClassName get() = "$packageName.R"

        fun toValue(helperName: String?) = Value.Resource(
            resourceClassName = resourceClassName,
            resourceType = type,
            resourceName = name,
            helperName = helperName
        )
    }

    private fun typedArrayValueKind(name: String) = when (name) {
        "getText", "getString" -> ValueKind.TEXT
        "getBoolean" -> ValueKind.BOOLEAN
        "getInt", "getInteger" -> ValueKind.INTEGER
        "getFloat", "getDimension" -> ValueKind.FLOAT
        "getDimensionPixelOffset" -> ValueKind.DIMENSION_PIXEL_OFFSET
        "getDimensionPixelSize" -> ValueKind.DIMENSION_PIXEL_SIZE
        "getDrawable" -> ValueKind.DRAWABLE
        "getColor" -> ValueKind.COLOR
        "getColorStateList" -> ValueKind.COLOR_STATE_LIST
        "getResourceId" -> ValueKind.RESOURCE_ID
        else -> null
    }

    private enum class ValueKind {
        TARGET_TYPE,
        TEXT,
        BOOLEAN,
        INTEGER,
        FLOAT,
        DIMENSION_PIXEL_OFFSET,
        DIMENSION_PIXEL_SIZE,
        DRAWABLE,
        COLOR,
        COLOR_STATE_LIST,
        RESOURCE_ID
    }
}