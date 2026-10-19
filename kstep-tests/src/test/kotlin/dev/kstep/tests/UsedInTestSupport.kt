package dev.kstep.tests

import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.InstanceDescription
import dev.kstep.express.validation.InstanceEvaluationContext
import dev.kstep.express.validation.OutgoingReference
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleValue

/** A synthetic instance used by the USEDIN / INVERSE tests; its [entity] is the concrete EXPRESS entity name. */
internal class GNode(
    val entity: String,
    val values: Map<String, WhereRuleValue> = emptyMap(),
) {
    val refs = mutableListOf<OutgoingReference>()

    fun ref(
        declaringEntity: String,
        attribute: String,
        vararg targets: GNode,
    ): GNode {
        refs += OutgoingReference(declaringEntity, attribute, targets.toList())
        return this
    }
}

internal val gNodeDescriber =
    InstanceDescriber { instance ->
        (instance as? GNode)?.let { InstanceDescription(it.entity, it.values, it.refs.toList()) }
    }

internal const val USEDIN_TEST_SCHEMA_SOURCE = """
SCHEMA usedin_test;
ENTITY owner_e;
  INVERSE
    items    : SET [1:?] OF item FOR parent;
    open_set : SET OF item FOR parent;
    exact_two : SET [2:2] OF item FOR parent;
    single   : item FOR base_item.parent;
    all_bag  : BAG [0:?] OF item FOR parent;
    base_set : SET OF base_item FOR parent;
    bad_lower_upper : SET [3:1] OF item FOR parent;
    negative : SET [-1:?] OF item FOR parent;
    symbolic : SET [n:?] OF item FOR parent;
    huge     : SET [99999999999999999999:?] OF item FOR parent;
    wrong_target : SET OF unrelated FOR link;
    missing_attr : SET OF item FOR nope;
END_ENTITY;
ENTITY base_item;
  parent : owner_e;
END_ENTITY;
ENTITY item SUBTYPE OF (base_item);
  tag : STRING;
END_ENTITY;
ENTITY other_item;
  parent : owner_e;
END_ENTITY;
ENTITY unrelated;
  link : item;
END_ENTITY;
END_SCHEMA;
"""

internal val usedInTestResolved: Map<String, ResolvedEntity> by lazy {
    InheritanceResolver.resolve(ExpressSemanticModelBuilder.build(USEDIN_TEST_SCHEMA_SOURCE).schemas.single())
}

internal val usedInTestRoleResolver: UsedInRoleResolver by lazy {
    UsedInRoleResolver(
        usedInTestResolved.values.toList(),
        "usedin_test",
        setOf("ALIAS_SCHEMA"),
        mapOf("ALIAS_SCHEMA.GHOST.THING" to "ghost entity is not modelled"),
    )
}

internal fun usedInContext(
    self: GNode,
    vararg others: GNode,
): InstanceEvaluationContext {
    val index = ReverseReferenceIndex.build(listOf(self) + others.toList(), gNodeDescriber)
    return InstanceEvaluationContext(self, self.entity, index, usedInTestRoleResolver)
}
