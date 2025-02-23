/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.actions.generate

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.IncorrectOperationException
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.core.overrideImplement.OverrideMemberChooserObject
import org.jetbrains.kotlin.idea.core.overrideImplement.generateMember
import org.jetbrains.kotlin.idea.quickfix.insertMembersAfter
import org.jetbrains.kotlin.idea.refactoring.quoteIfNeeded
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getElementTextWithContext
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull
import java.util.*

private tailrec fun ClassDescriptor.findDeclaredFunction (
        name: String,
        checkSuperClasses: Boolean,
        filter: (FunctionDescriptor) -> Boolean
): FunctionDescriptor? {
    unsubstitutedMemberScope
            .getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_IDE)
            .firstOrNull { it.containingDeclaration == this && it.kind == CallableMemberDescriptor.Kind.DECLARATION && filter(it) }
            ?.let { return it }

    return if (checkSuperClasses) getSuperClassOrAny().findDeclaredFunction(name, checkSuperClasses, filter) else null
}

fun ClassDescriptor.findDeclaredEquals(checkSupers: Boolean): FunctionDescriptor? {
    return findDeclaredFunction("equals", checkSupers) {
        it.valueParameters.singleOrNull()?.type == it.builtIns.nullableAnyType && it.typeParameters.isEmpty()
    }
}

fun ClassDescriptor.findDeclaredHashCode(checkSupers: Boolean): FunctionDescriptor? {
    return findDeclaredFunction("hashCode", checkSupers) { it.valueParameters.isEmpty() && it.typeParameters.isEmpty() }
}

class KotlinGenerateEqualsAndHashcodeAction : KotlinGenerateMemberActionBase<KotlinGenerateEqualsAndHashcodeAction.Info>() {
    companion object {
        private val LOG = Logger.getInstance(KotlinGenerateEqualsAndHashcodeAction::class.java)

        private val MEMBER_RENDERER = IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_IN_TYPES.withOptions {
            modifiers = emptySet()
            startFromName = true
            parameterNameRenderingPolicy = ParameterNameRenderingPolicy.NONE
        }
    }

    class Info(
            val needEquals: Boolean,
            val needHashCode: Boolean,
            val classDescriptor: ClassDescriptor,
            val variablesForEquals: List<VariableDescriptor>,
            val variablesForHashCode: List<VariableDescriptor>
    )

    override fun isValidForClass(targetClass: KtClassOrObject): Boolean {
        return targetClass is KtClass && targetClass !is KtEnumEntry && !targetClass.isAnnotation()
                && !targetClass.hasModifier(KtTokens.DATA_KEYWORD)
                && targetClass.getPropertiesToUse().isNotEmpty()
    }

    private fun KtClassOrObject.getPropertiesToUse(): List<KtNamedDeclaration> {
        return ArrayList<KtNamedDeclaration>().apply {
            getPrimaryConstructorParameters().filterTo(this) { it.hasValOrVar() }
            declarations.filterIsInstance<KtProperty>().filterTo(this) f@ {
                val descriptor = it.resolveToDescriptor()
                when (descriptor) {
                    is ValueParameterDescriptor -> true
                    is PropertyDescriptor -> descriptor.accessors.all { it.isDefault }
                    else -> false
                }
            }
        }
    }

    private fun confirmRewrite(
            targetClass: KtClass,
            equalsDescriptor: FunctionDescriptor,
            hashCodeDescriptor: FunctionDescriptor
    ): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) return true
        val functionsText = "'${MEMBER_RENDERER.render(equalsDescriptor)}' and '${MEMBER_RENDERER.render(hashCodeDescriptor)}'"
        val message = "Functions $functionsText are already defined\nfor class ${targetClass.name}. Do you want to delete them and proceed?"
        return Messages.showYesNoDialog(targetClass.project, message,
                                        CodeInsightBundle.message("generate.equals.and.hashcode.already.defined.title"),
                                        Messages.getQuestionIcon()) == Messages.YES
    }

    override fun prepareMembersInfo(klass: KtClassOrObject, project: Project, editor: Editor?): Info? {
        if (klass !is KtClass) throw AssertionError("Not a class: ${klass.getElementTextWithContext()}")

        val context = klass.analyzeFully()
        val classDescriptor = context.get(BindingContext.CLASS, klass) ?: return null

        val equalsDescriptor = classDescriptor.findDeclaredEquals(false)
        val hashCodeDescriptor = classDescriptor.findDeclaredHashCode(false)

        var needEquals = equalsDescriptor == null
        var needHashCode = hashCodeDescriptor == null
        if (!needEquals && !needHashCode) {
            if (!confirmRewrite(klass, equalsDescriptor!!, hashCodeDescriptor!!)) return null

            runWriteAction {
                try {
                    equalsDescriptor.source.getPsi()?.delete()
                    hashCodeDescriptor.source.getPsi()?.delete()
                    needEquals = true
                    needHashCode = true
                } catch(e: IncorrectOperationException) {
                    LOG.error(e)
                }
            }
        }

        val properties = klass.getPropertiesToUse()

        if (properties.isEmpty() || ApplicationManager.getApplication().isUnitTestMode) {
            val descriptors = properties.map { context[BindingContext.DECLARATION_TO_DESCRIPTOR, it] as VariableDescriptor }
            return Info(needEquals, needHashCode, classDescriptor, descriptors, descriptors)
        }

        return with(KotlinGenerateEqualsWizard(project, klass, properties, needEquals, needHashCode)) {
            if (!showAndGet()) return null

            Info(needEquals,
                 needHashCode,
                 classDescriptor,
                 getPropertiesForEquals().map { context[BindingContext.DECLARATION_TO_DESCRIPTOR, it] as VariableDescriptor },
                 getPropertiesForHashCode().map { context[BindingContext.DECLARATION_TO_DESCRIPTOR, it] as VariableDescriptor })
        }
    }

    private fun generateFunctionSkeleton(descriptor: FunctionDescriptor, project: Project): KtNamedFunction {
        return OverrideMemberChooserObject
                .create(project, descriptor, descriptor, OverrideMemberChooserObject.BodyType.EMPTY)
                .generateMember(project) as KtNamedFunction
    }

    private fun generateEquals(project: Project, info: Info): KtNamedFunction? {
        with(info) {
            if (!needEquals) return null

            val superEquals = classDescriptor.getSuperClassOrAny().findDeclaredEquals(true)!!
            val equalsFun = generateFunctionSkeleton(superEquals, project)

            val paramName = equalsFun.valueParameters.first().name!!.quoteIfNeeded()
            var typeForCast = IdeDescriptorRenderers.SOURCE_CODE.renderClassifierName(classDescriptor)
            val typeParams = classDescriptor.declaredTypeParameters
            if (typeParams.isNotEmpty()) {
                typeForCast += typeParams.map { "*" }.joinToString(prefix = "<", postfix = ">")
            }

            val useIsCheck = CodeInsightSettings.getInstance().USE_INSTANCEOF_ON_EQUALS_PARAMETER
            val isNotInstanceCondition = if (useIsCheck) "$paramName !is $typeForCast" else "$paramName?.javaClass != javaClass"
            val bodyText = StringBuilder().apply {
                append("if (this === $paramName) return true\n")
                append("if ($isNotInstanceCondition) return false\n")

                val builtIns = superEquals.builtIns
                if (!builtIns.isMemberOfAny(superEquals)) {
                    append("if (!super.equals($paramName)) return false\n")
                }

                if (variablesForEquals.isNotEmpty()) {
                    if (!useIsCheck) {
                        append("\n$paramName as $typeForCast\n")
                    }

                    append('\n')

                    variablesForEquals.forEach {
                        val propName = it.name.asString()
                        val notEquals = when {
                            KotlinBuiltIns.isArray(it.type) || KotlinBuiltIns.isPrimitiveArray(it.type) ->
                                "!java.util.Arrays.equals($propName, $paramName.$propName)"
                            else ->
                                "$propName != $paramName.$propName"
                        }
                        append("if ($notEquals) return false\n")
                    }

                    append('\n')
                }

                append("return true")
            }

            equalsFun.bodyExpression!!.replace(KtPsiFactory(project).createExpression("{\n$bodyText\n}"))

            return equalsFun
        }
    }
    private fun generateHashCode(project: Project, info: Info): KtNamedFunction? {
        fun VariableDescriptor.genVariableHashCode(parenthesesNeeded: Boolean): String {
            val ref = name.asString().quoteIfNeeded()
            val isNullable = TypeUtils.isNullableType(type)

            val builtIns = builtIns

            val typeClass = type.constructor.declarationDescriptor
            var text = when {
                typeClass == builtIns.byte || typeClass == builtIns.short || typeClass == builtIns.int ->
                    ref
                KotlinBuiltIns.isArray(type) || KotlinBuiltIns.isPrimitiveArray(type) ->
                    if (isNullable) "$ref?.let { java.util.Arrays.hashCode(it) }" else "java.util.Arrays.hashCode($ref)"
                else ->
                    if (isNullable) "$ref?.hashCode()" else "$ref.hashCode()"
            }
            if (isNullable) {
                text += " ?: 0"
                if (parenthesesNeeded) {
                    text = "($text)"
                }
            }

            return text
        }

        with(info) {
            if (!needHashCode) return null

            val superHashCode = classDescriptor.getSuperClassOrAny().findDeclaredHashCode(true)!!
            val hashCodeFun = generateFunctionSkeleton(superHashCode, project)
            val builtins = superHashCode.builtIns

            val propertyIterator = variablesForHashCode.iterator()
            val initialValue = when {
                !builtins.isMemberOfAny(superHashCode) -> "super.hashCode()"
                propertyIterator.hasNext() -> propertyIterator.next().genVariableHashCode(false)
                else -> "0"
            }

            val bodyText = if (propertyIterator.hasNext()) {
                val validator = CollectingNameValidator(variablesForEquals.map { it.name.asString().quoteIfNeeded() })
                val resultVarName = KotlinNameSuggester.suggestNameByName("result", validator)
                StringBuilder().apply {
                    append("var $resultVarName = $initialValue\n")
                    propertyIterator.forEach { append("$resultVarName += 31 * $resultVarName + ${it.genVariableHashCode(true)}\n") }
                    append("return $resultVarName")
                }.toString()
            } else "return $initialValue"

            hashCodeFun.bodyExpression!!.replace(KtPsiFactory(project).createExpression("{\n$bodyText\n}"))

            return hashCodeFun
        }
    }

    override fun generateMembers(project: Project, editor: Editor?, info: Info): List<KtDeclaration> {
        val targetClass = info.classDescriptor.source.getPsi() as KtClass
        val prototypes = ArrayList<KtDeclaration>(2)
                .apply {
                    addIfNotNull(generateEquals(project, info))
                    addIfNotNull(generateHashCode(project, info))
                }
        val anchor = with(targetClass.declarations) { lastIsInstanceOrNull<KtNamedFunction>() ?: lastOrNull() }
        return insertMembersAfter(editor, targetClass, prototypes, anchor)
    }
}
