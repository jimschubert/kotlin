/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.k2js.translate.intrinsic.operation

import com.google.dart.compiler.backend.js.ast.*
import org.jetbrains.jet.lang.psi.JetBinaryExpression
import org.jetbrains.jet.lang.types.expressions.OperatorConventions
import org.jetbrains.k2js.translate.context.TranslationContext
import org.jetbrains.k2js.translate.operation.OperatorTable
import org.jetbrains.k2js.translate.utils.JsDescriptorUtils

import org.jetbrains.k2js.translate.utils.PsiUtils.getOperationToken

import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import org.jetbrains.jet.lexer.JetToken
import com.google.common.collect.ImmutableSet


object CompareToBOIF : BinaryOperationIntrinsicFactory {

    private object CompareToIntrinsic : AbstractBinaryOperationIntrinsic() {
        override fun apply(expression: JetBinaryExpression, left: JsExpression, right: JsExpression, context: TranslationContext): JsExpression {
            val operator = OperatorTable.getBinaryOperator(getOperationToken(expression))
            return JsBinaryOperation(operator, left, right)
        }
    }

    override public fun getSupportTokens(): ImmutableSet<JetToken> = OperatorConventions.COMPARISON_OPERATIONS

    override public fun getIntrinsic(descriptor: FunctionDescriptor): BinaryOperationIntrinsic? {
        return if (JsDescriptorUtils.isBuiltin(descriptor)) CompareToIntrinsic else null
    }
}
