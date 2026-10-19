package dev.kstep.express.codegen

import com.squareup.kotlinpoet.ClassName
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressSelectItem
import dev.kstep.express.semantic.ResolvedEntity

/**
 * Per-schema SELECT analysis (ADR-0020): the validated membership graph plus, for every Kotlin
 * type that has to implement one or more generated `sealed interface`s, the list of those
 * interfaces in SELECT declaration order (so emitted output is deterministic).
 *
 * All maps are keyed by the lowercase EXPRESS name (EXPRESS identifiers are case-insensitive).
 */
class SelectModel internal constructor(
    /** select lower-name -> its declaration (closed SELECT, validated). */
    val selects: Map<String, ExpressDefinedType>,
    /** entity lower-name -> select ClassNames it must implement (direct member or via an ancestor). */
    val entitySuperinterfaces: Map<String, List<ClassName>>,
    /** enum lower-name -> select ClassNames. */
    val enumSuperinterfaces: Map<String, List<ClassName>>,
    /** select lower-name -> parent select ClassNames (nested SELECT). */
    val selectSuperinterfaces: Map<String, List<ClassName>>,
    /** alias-TYPE lower-name -> select ClassNames; each key needs one shared top-level wrapper class. */
    val wrapperSuperinterfaces: Map<String, List<ClassName>>,
) {
    companion object {
        val EMPTY =
            SelectModel(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

object SelectResolution {
    /**
     * Validates every SELECT in [selectNamesToEmit] and computes the [SelectModel].
     *
     * @param definedTypes the schema's full TYPE map, lowercase-keyed, in declaration order.
     * @param resolvedEntities inheritance-resolved entities, keyed by original name.
     * @param emittedEntityNames lowercase names of the entities that will actually be emitted
     *   (instantiable and present in the schema being generated).
     * @param selectNamesToEmit lowercase names of closed SELECTs that will be emitted.
     * @param enumNamesToEmit lowercase names of ENUMERATIONs that will be emitted.
     */
    fun analyze(
        schemaName: String,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType>,
        resolvedEntities: Map<String, ResolvedEntity>,
        emittedEntityNames: Set<String>,
        selectNamesToEmit: Set<String>,
        enumNamesToEmit: Set<String>,
    ): SelectModel {
        if (selectNamesToEmit.isEmpty()) return SelectModel.EMPTY

        // Declaration order of the SELECTs to emit (definedTypes preserves schema order).
        val orderedSelects = definedTypes.filter { (key, dt) -> key in selectNamesToEmit && dt.selectItems != null }
        val resolvedByLower = resolvedEntities.values.associateBy { it.entity.name.lowercase() }
        val emittedInstantiable =
            resolvedByLower.filter { (key, resolved) -> key in emittedEntityNames && resolved.isInstantiable }

        val entitySupers = linkedMapOf<String, MutableList<ClassName>>()
        val enumSupers = linkedMapOf<String, MutableList<ClassName>>()
        val selectSupers = linkedMapOf<String, MutableList<ClassName>>()
        val wrapperSupers = linkedMapOf<String, MutableList<ClassName>>()

        orderedSelects.forEach { (_, select) ->
            val selectClass = ClassName(packageName, NamingConventions.toClassName(select.name))
            val items = checkNotNull(select.selectItems)
            val seenMembers = mutableSetOf<String>()
            items.forEach { item ->
                val memberKey = item.name.lowercase()
                val where = "schema '$schemaName': SELECT TYPE '${select.name}' member '${item.name}'"
                if (!seenMembers.add(memberKey)) {
                    throw CodeGenException("$where is listed more than once (EXPRESS names are case-insensitive)")
                }
                when (item) {
                    is ExpressSelectItem.Entity ->
                        addEntityMember(where, item, selectClass, resolvedByLower, emittedInstantiable, entitySupers)
                    is ExpressSelectItem.DefinedType ->
                        addTypeMember(
                            where,
                            item,
                            selectClass,
                            packageName,
                            definedTypes,
                            selectNamesToEmit,
                            enumNamesToEmit,
                            enumSupers,
                            selectSupers,
                            wrapperSupers,
                        )
                }
            }
        }
        checkNoSelectCycleOrOverdepth(schemaName, orderedSelects.keys, definedTypes)
        return SelectModel(
            orderedSelects,
            entitySupers.mapValues { it.value.toList() },
            enumSupers.mapValues { it.value.toList() },
            selectSupers.mapValues { it.value.toList() },
            wrapperSupers.mapValues { it.value.toList() },
        )
    }

    private fun addEntityMember(
        where: String,
        item: ExpressSelectItem.Entity,
        selectClass: ClassName,
        resolvedByLower: Map<String, ResolvedEntity>,
        emittedInstantiable: Map<String, ResolvedEntity>,
        entitySupers: MutableMap<String, MutableList<ClassName>>,
    ) {
        val memberKey = item.name.lowercase()
        // EXPRESS SELECT membership includes every subtype of a member entity, so every emitted
        // instantiable entity whose self-or-ancestor chain contains the member implements the SELECT.
        val implementors =
            emittedInstantiable.filter { (_, resolved) ->
                resolved.entity.name.equals(item.name, ignoreCase = true) ||
                    resolved.ancestorChain.any { it.equals(item.name, ignoreCase = true) }
            }
        if (implementors.isEmpty()) {
            val known = if (memberKey in resolvedByLower) "" else " (entity is not part of this schema's resolved set)"
            throw CodeGenException(
                "$where (entity) has no emitted Kotlin implementor$known: it is abstract with no emitted " +
                    "instantiable subtype, or it was filtered out of the emitted entity set; extend the " +
                    "emitted entity set so at least one implementor exists",
            )
        }
        implementors.keys.forEach { key -> entitySupers.getOrPut(key) { mutableListOf() }.addUnique(selectClass) }
    }

    private fun addTypeMember(
        where: String,
        item: ExpressSelectItem.DefinedType,
        selectClass: ClassName,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType>,
        selectNamesToEmit: Set<String>,
        enumNamesToEmit: Set<String>,
        enumSupers: MutableMap<String, MutableList<ClassName>>,
        selectSupers: MutableMap<String, MutableList<ClassName>>,
        wrapperSupers: MutableMap<String, MutableList<ClassName>>,
    ) {
        val memberKey = item.name.lowercase()
        val member =
            definedTypes[memberKey]
                ?: throw CodeGenException("$where (TYPE) is not a known TYPE of this schema")
        member.unsupportedReason?.let { throw CodeGenException("$where is unsupported: $it") }
        when {
            member.selectItems != null -> {
                if (memberKey !in selectNamesToEmit) {
                    throw CodeGenException("$where is a nested SELECT that is not part of the emitted type set")
                }
                selectSupers.getOrPut(memberKey) { mutableListOf() }.addUnique(selectClass)
            }
            member.enumerationItems != null -> {
                if (memberKey !in enumNamesToEmit) {
                    throw CodeGenException("$where is an ENUMERATION that is not part of the emitted type set")
                }
                enumSupers.getOrPut(memberKey) { mutableListOf() }.addUnique(selectClass)
            }
            member.underlyingSimpleType != null || member.aliasedTypeName != null -> {
                // Throws (with the failing link named) for aliases ending in an entity, an
                // EXTENSIBLE/BASED_ON form, an aggregation or an unsupported simple type.
                resolveKotlinTypeName(DefinedTypeRef(member.name), packageName, where, definedTypes)
                wrapperSupers.getOrPut(memberKey) { mutableListOf() }.addUnique(selectClass)
            }
            else ->
                throw CodeGenException(
                    "$where is an aggregation-backed TYPE (or another form with no Kotlin mapping); " +
                        "it cannot be a SELECT member",
                )
        }
    }

    // Memoised DFS over SELECT-in-SELECT membership with an explicit path stack, so a cycle is
    // reported as `a -> b -> a`, the nesting depth is bounded by MAX_SELECT_NESTING_DEPTH (the
    // descent aborts as soon as the path would exceed it, so recursion stays <= 32 frames) and
    // diamond-shaped SELECT DAGs are not re-explored exponentially.
    private fun checkNoSelectCycleOrOverdepth(
        schemaName: String,
        selectKeys: Collection<String>,
        definedTypes: Map<String, ExpressDefinedType>,
    ) {
        val memo = mutableMapOf<String, Int>()
        val path = ArrayList<String>()

        fun nameOf(key: String) = definedTypes[key]?.name ?: key

        fun depthOf(key: String): Int {
            memo[key]?.let { return it }
            if (key in path) {
                val cycle = (path.dropWhile { it != key } + key).joinToString(" -> ") { nameOf(it) }
                throw CodeGenException("schema '$schemaName': SELECT TYPE nesting is cyclic ($cycle)")
            }
            if (path.size >= MAX_SELECT_NESTING_DEPTH) {
                throw CodeGenException(
                    "schema '$schemaName': SELECT TYPE nesting starting at '${nameOf(path.first())}' exceeds " +
                        "MAX_SELECT_NESTING_DEPTH ($MAX_SELECT_NESTING_DEPTH) at '${nameOf(key)}' " +
                        "(depth limit, not a cycle)",
                )
            }
            path.add(key)
            var deepest = 0
            definedTypes[key]
                ?.selectItems
                .orEmpty()
                .filterIsInstance<ExpressSelectItem.DefinedType>()
                .map { it.name.lowercase() }
                .filter { definedTypes[it]?.selectItems != null }
                .forEach { child -> deepest = maxOf(deepest, depthOf(child)) }
            path.removeAt(path.lastIndex)
            return (deepest + 1).also { memo[key] = it }
        }

        selectKeys.forEach { depthOf(it) }
    }

    private fun MutableList<ClassName>.addUnique(value: ClassName) {
        if (value !in this) add(value)
    }
}
