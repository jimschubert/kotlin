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

package org.jetbrains.kotlin.resolve.calls.smartcasts

import com.google.common.collect.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.*

import java.util.*

import org.jetbrains.kotlin.resolve.calls.smartcasts.Nullability.NOT_NULL

internal class DelegatingDataFlowInfo @JvmOverloads constructor(
        private val parent: DataFlowInfo?,
        private val nullabilityInfo: ImmutableMap<DataFlowValue, Nullability>,
        // Also immutable
        private val typeInfo: SetMultimap<DataFlowValue, KotlinType>,
        /**
         * Value for which type info was cleared or reassigned at this point
         * so parent type info should not be in use
         */
        private val valueWithGivenTypeInfo: DataFlowValue? = null
) : DataFlowInfo {

    override fun getCompleteNullabilityInfo(): Map<DataFlowValue, Nullability> {
        val result = Maps.newHashMap<DataFlowValue, Nullability>()
        var info: DelegatingDataFlowInfo? = this
        while (info != null) {
            for ((key, value) in info.nullabilityInfo) {
                if (!result.containsKey(key)) {
                    result.put(key, value)
                }
            }
            info = info.parent as DelegatingDataFlowInfo?
        }
        return result
    }

    override fun getCompleteTypeInfo(): SetMultimap<DataFlowValue, KotlinType> {
        val result = newTypeInfo()
        val withGivenTypeInfo = HashSet<DataFlowValue>()
        var info: DelegatingDataFlowInfo? = this
        while (info != null) {
            for (key in info.typeInfo.keySet()) {
                if (!withGivenTypeInfo.contains(key)) {
                    result.putAll(key, info.typeInfo.get(key))
                }
            }
            info.valueWithGivenTypeInfo?.let { withGivenTypeInfo.add(it) }
            info = info.parent as DelegatingDataFlowInfo?
        }
        return result
    }

    override fun getCollectedNullability(key: DataFlowValue) = getNullability(key, false)

    override fun getPredictableNullability(key: DataFlowValue) = getNullability(key, true)

    private fun getNullability(key: DataFlowValue, predictableOnly: Boolean) =
            if (predictableOnly && !key.isPredictable) {
                key.immanentNullability
            }
            else {
                nullabilityInfo[key] ?: if (parent != null) {
                    parent.getCollectedNullability(key)
                }
                else {
                    key.immanentNullability
                }
            }

    private fun putNullability(map: MutableMap<DataFlowValue, Nullability>, value: DataFlowValue, nullability: Nullability): Boolean {
        map.put(value, nullability)
        return nullability != getCollectedNullability(value)
    }

    override fun getCollectedTypes(key: DataFlowValue) = getCollectedTypes(key, true)

    private fun getCollectedTypes(key: DataFlowValue, enrichWithNotNull: Boolean): Set<KotlinType> {
        val types = collectTypesFromMeAndParents(key)
        if (!enrichWithNotNull || getCollectedNullability(key).canBeNull()) {
            return types
        }

        val enrichedTypes = Sets.newHashSetWithExpectedSize<KotlinType>(types.size + 1)
        val originalType = key.type
        if (originalType.isMarkedNullable) {
            enrichedTypes.add(TypeUtils.makeNotNullable(originalType))
        }
        for (type in types) {
            enrichedTypes.add(TypeUtils.makeNotNullable(type))
        }

        return enrichedTypes
    }

    override fun getPredictableTypes(key: DataFlowValue) = getPredictableTypes(key, true)

    private fun getPredictableTypes(key: DataFlowValue, enrichWithNotNull: Boolean) =
            if (!key.isPredictable) LinkedHashSet() else getCollectedTypes(key, enrichWithNotNull)

    /**
     * Call this function to clear all data flow information about
     * the given data flow value.

     * @param value
     */
    override fun clearValueInfo(value: DataFlowValue): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        putNullability(builder, value, Nullability.UNKNOWN)
        return DelegatingDataFlowInfo(
                this,
                ImmutableMap.copyOf(builder),
                EMPTY_TYPE_INFO,
                value)
    }

    override fun assign(a: DataFlowValue, b: DataFlowValue): DataFlowInfo {
        val nullability = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfB = getPredictableNullability(b)
        putNullability(nullability, a, nullabilityOfB)

        val newTypeInfo = newTypeInfo()
        var typesForB = getPredictableTypes(b)
        // Own type of B must be recorded separately, e.g. for a constant
        // But if its type is the same as A or it's null, there is no reason to do it
        // because usually null type or own type are not saved in this set
        if (nullabilityOfB.canBeNonNull() && a.type != b.type) {
            typesForB += b.type
        }
        newTypeInfo.putAll(a, typesForB)

        return DelegatingDataFlowInfo(
                this,
                ImmutableMap.copyOf(nullability),
                if (newTypeInfo.isEmpty) EMPTY_TYPE_INFO else newTypeInfo,
                a)
    }

    override fun equate(a: DataFlowValue, b: DataFlowValue): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfA = getPredictableNullability(a)
        val nullabilityOfB = getPredictableNullability(b)

        var changed = putNullability(builder, a, nullabilityOfA.refine(nullabilityOfB)) or
                      putNullability(builder, b, nullabilityOfB.refine(nullabilityOfA))

        val newTypeInfo = newTypeInfo()
        newTypeInfo.putAll(a, getPredictableTypes(b, false))
        newTypeInfo.putAll(b, getPredictableTypes(a, false))
        if (a.type != b.type) {
            // To avoid recording base types of own type
            if (!a.type.isSubtypeOf(b.type)) {
                newTypeInfo.put(a, b.type)
            }
            if (!b.type.isSubtypeOf(a.type)) {
                newTypeInfo.put(b, a.type)
            }
        }
        changed = changed or !newTypeInfo.isEmpty

        return if (!changed) {
            this
        }
        else {
            DelegatingDataFlowInfo(this, ImmutableMap.copyOf(builder), if (newTypeInfo.isEmpty) EMPTY_TYPE_INFO else newTypeInfo)
        }
    }

    private fun collectTypesFromMeAndParents(value: DataFlowValue): Set<KotlinType> {
        val types = LinkedHashSet<KotlinType>()

        var current: DataFlowInfo? = this
        while (current != null) {
            if (current is DelegatingDataFlowInfo) {
                types.addAll(current.typeInfo.get(value))
                if (value == current.valueWithGivenTypeInfo) {
                    current = null
                }
                else {
                    current = current.parent
                }
            }
            else {
                types.addAll(current.getCollectedTypes(value))
                break
            }
        }

        return types
    }

    override fun disequate(a: DataFlowValue, b: DataFlowValue): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfA = getPredictableNullability(a)
        val nullabilityOfB = getPredictableNullability(b)

        var changed = putNullability(builder, a, nullabilityOfA.refine(nullabilityOfB.invert())) or
                      putNullability(builder, b, nullabilityOfB.refine(nullabilityOfA.invert()))
        return if (changed) DelegatingDataFlowInfo(this, ImmutableMap.copyOf(builder), EMPTY_TYPE_INFO) else this
    }

    override fun establishSubtyping(value: DataFlowValue, type: KotlinType): DataFlowInfo {
        if (value.type == type) return this
        if (getCollectedTypes(value).contains(type)) return this
        if (!value.type.isFlexible() && value.type.isSubtypeOf(type)) return this
        val newNullabilityInfo = if (type.isMarkedNullable) EMPTY_NULLABILITY_INFO else ImmutableMap.of(value, NOT_NULL)
        val newTypeInfo = ImmutableSetMultimap.of(value, type)
        return DelegatingDataFlowInfo(this, newNullabilityInfo, newTypeInfo)
    }

    override fun and(otherInfo: DataFlowInfo): DataFlowInfo {
        if (otherInfo === DataFlowInfo.EMPTY) return this
        if (this === DataFlowInfo.EMPTY) return otherInfo
        if (this === otherInfo) return this

        assert(otherInfo is DelegatingDataFlowInfo) { "Unknown DataFlowInfo type: " + otherInfo }
        val other = otherInfo as DelegatingDataFlowInfo

        val nullabilityMapBuilder = Maps.newHashMap<DataFlowValue, Nullability>()
        for ((key, otherFlags) in other.completeNullabilityInfo) {
            val thisFlags = getCollectedNullability(key)
            val flags = thisFlags.and(otherFlags)
            if (flags != thisFlags) {
                nullabilityMapBuilder.put(key, flags)
            }
        }

        val myTypeInfo = completeTypeInfo
        val otherTypeInfo = other.completeTypeInfo
        if (nullabilityMapBuilder.isEmpty() && containsAll(myTypeInfo, otherTypeInfo)) {
            return this
        }

        return DelegatingDataFlowInfo(this, ImmutableMap.copyOf(nullabilityMapBuilder), otherTypeInfo)
    }

    private fun Set<KotlinType>.containsNothing() = any { KotlinBuiltIns.isNothing(it) }

    private fun Set<KotlinType>.intersect(other: Set<KotlinType>) =
            if (other.containsNothing()) this
            else if (this.containsNothing()) other
            else Sets.intersection(this, other)

    override fun or(otherInfo: DataFlowInfo): DataFlowInfo {
        if (otherInfo === DataFlowInfo.EMPTY) return DataFlowInfo.EMPTY
        if (this === DataFlowInfo.EMPTY) return DataFlowInfo.EMPTY
        if (this === otherInfo) return this

        assert(otherInfo is DelegatingDataFlowInfo) { "Unknown DataFlowInfo type: " + otherInfo }
        val other = otherInfo as DelegatingDataFlowInfo

        val nullabilityMapBuilder = Maps.newHashMap<DataFlowValue, Nullability>()
        for ((key, otherFlags) in other.completeNullabilityInfo) {
            val thisFlags = getCollectedNullability(key)
            nullabilityMapBuilder.put(key, thisFlags.or(otherFlags))
        }

        val myTypeInfo = completeTypeInfo
        val otherTypeInfo = other.completeTypeInfo
        val newTypeInfo = newTypeInfo()

        for (key in Sets.intersection(myTypeInfo.keySet(), otherTypeInfo.keySet())) {
            newTypeInfo.putAll(key, myTypeInfo[key].intersect(otherTypeInfo[key]))
        }

        if (nullabilityMapBuilder.isEmpty() && newTypeInfo.isEmpty) {
            return DataFlowInfo.EMPTY
        }

        return DelegatingDataFlowInfo(null, ImmutableMap.copyOf(nullabilityMapBuilder), newTypeInfo)
    }

    override fun toString() = if (typeInfo.isEmpty && nullabilityInfo.isEmpty()) "EMPTY" else "Non-trivial DataFlowInfo"

    companion object {
        private val EMPTY_NULLABILITY_INFO = ImmutableMap.of<DataFlowValue, Nullability>()
        private val EMPTY_TYPE_INFO = newTypeInfo()

        private fun containsAll(first: SetMultimap<DataFlowValue, KotlinType>, second: SetMultimap<DataFlowValue, KotlinType>) =
                first.entries().containsAll(second.entries())

        @JvmStatic
        fun newTypeInfo(): SetMultimap<DataFlowValue, KotlinType> = LinkedHashMultimap.create<DataFlowValue, KotlinType>()
    }
}
