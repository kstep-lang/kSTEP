package dev.kstep.tests

import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.validation.InstanceEvaluationContext
import dev.kstep.express.validation.InverseAttributeEvaluator
import dev.kstep.express.validation.PreparationOutcome
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.SchemaFunctionIntrinsicSet
import dev.kstep.express.validation.SchemaRulePreparer
import dev.kstep.express.validation.UniqueRuleEvaluator
import dev.kstep.express.validation.UnsupportedUniqueRuleException
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleEvaluator
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private const val SCHEMA_SOURCE = """
SCHEMA uq_test;
TYPE end_t = SELECT (part_e, other_e);
END_TYPE;
ENTITY base_e;
  k : STRING;
  o : OPTIONAL STRING;
DERIVE
  k_copy : STRING := k;
UNIQUE
  UR1: k, o;
  UR3: k_copy;
WHERE
  WR1: SELF.k <> '';
END_ENTITY;
ENTITY sub_e
  SUBTYPE OF (base_e);
  r : base_e;
UNIQUE
  UR2: r, SELF\base_e.k;
WHERE
  WR2: SIZEOF(USEDIN(SELF, 'uq_test.BASE_E.R')) >= 0;
END_ENTITY;
ENTITY part_e;
  INVERSE
    links : SET OF link_e FOR target;
END_ENTITY;
ENTITY other_e;
  v : STRING;
END_ENTITY;
ENTITY link_e;
  target : end_t;
END_ENTITY;
ENTITY chain_e;
  next : end_t;
  tag : STRING;
END_ENTITY;
END_SCHEMA;
"""

private val schema by lazy { ExpressSemanticModelBuilder.build(SCHEMA_SOURCE).schemas.single() }
private val resolved by lazy { InheritanceResolver.resolve(schema) }
private val roleResolver by lazy { UsedInRoleResolver(resolved.values.toList(), "uq_test") }

private fun str(value: String): WhereRuleValue = WhereRuleValue.StringValue(value)

private fun base(
    k: String,
    o: String? = null,
    entity: String = "base_e",
) = GNode(entity, mapOf("k" to str(k), "o" to (o?.let { str(it) } ?: WhereRuleValue.Unset)))

private fun evaluate(vararg nodes: GNode): UniqueRuleEvaluator.Result =
    UniqueRuleEvaluator.evaluate(
        ReverseReferenceIndex.build(nodes.toList(), gNodeDescriber),
        resolved,
        roleResolver,
    )

class UniqueAndFlattenedRulesTest :
    StringSpec({
        // ------------------------------------------------------------------ inheritance flattening (G2)

        "flattened WHERE and UNIQUE rules list the ancestors' rules first, tagged with their declaring entity" {
            val sub = resolved.getValue("sub_e")
            sub.flattenedWhereRules.map { it.declaringEntity to it.rule.label } shouldContainExactly
                listOf("base_e" to "WR1", "sub_e" to "WR2")
            sub.flattenedUniqueRules.map { it.declaringEntity to it.rule.label } shouldContainExactly
                listOf("base_e" to "UR1", "base_e" to "UR3", "sub_e" to "UR2")
            resolved.getValue("base_e").flattenedWhereRules.map { it.rule.label } shouldContainExactly listOf("WR1")
            resolved.getValue("other_e").flattenedUniqueRules.shouldBeEmpty()
        }

        // ------------------------------------------------------------------ UNIQUE semantics

        "two instances that agree on every listed value violate the rule, naming both" {
            val a = base("x", "y")
            val b = base("x", "y")
            val result = evaluate(a, b, base("x", "z"))
            val violation = result.violations.single { it.rule.rule.label == "UR1" }
            violation.instances.size shouldBe 2
            violation.fieldValues shouldContainExactly listOf("k" to "'x'", "o" to "'y'")
            (violation.instances[0] === a && violation.instances[1] === b) shouldBe true
        }

        "an unset OPTIONAL value takes no part in the comparison: unknown is never equal to unknown" {
            val result = evaluate(base("x"), base("x"))
            result.violations.none { it.rule.rule.label == "UR1" } shouldBe true
            // ...while UR3 (a rule over a derived value that is known) does still fire
            result.violations
                .single()
                .rule.rule.label shouldBe "UR3"
        }

        "a rule over a derived attribute evaluates the derived value" {
            val result = evaluate(base("same", "1"), base("same", "2"))
            result.violations
                .single()
                .rule.rule.label shouldBe "UR3"
            result.violations.single().fieldValues shouldContainExactly listOf("k_copy" to "'same'")
        }

        "an inherited rule applies to subtype instances, entity references compare by identity" {
            val shared = base("p1", "q")
            val a = GNode("sub_e", mapOf("k" to str("k"), "o" to str("1"))).ref("sub_e", "r", shared)
            val b = GNode("sub_e", mapOf("k" to str("k"), "o" to str("2"))).ref("sub_e", "r", shared)
            val distinct = GNode("sub_e", mapOf("k" to str("k"), "o" to str("3"))).ref("sub_e", "r", base("p2", "q"))
            val result = evaluate(a, b, distinct)
            val ur2 = result.violations.single { it.rule.rule.label == "UR2" }
            ur2.rule.declaringEntity shouldBe "sub_e"
            ur2.instances.size shouldBe 2 // a and b share `r` by identity; `distinct` has an equal but different r
            ur2.fieldValues[1] shouldBe ("k" to "'k'")
            // UR3 (k_copy) was declared on base_e and covers all three subtype instances sharing k = 'k'
            result.violations
                .single { it.rule.rule.label == "UR3" }
                .instances.size shouldBe 3
        }

        "a large population is grouped by hashing, not compared pairwise" {
            val nodes = (1..60_000).map { base("k$it", "o") }.toTypedArray()
            val result = evaluate(*nodes)
            result.violations.shouldBeEmpty()
        }

        "an attribute the describer does not know is a loud error, never a silent pass" {
            val broken =
                ExpressSemanticModelBuilder
                    .build("SCHEMA b;\nENTITY e;\n  a : STRING;\nUNIQUE\n  UR1: nope;\nEND_ENTITY;\nEND_SCHEMA;")
                    .schemas
                    .single()
            val brokenResolved = InheritanceResolver.resolve(broken)
            val node = GNode("e", mapOf("a" to str("x")))
            shouldThrow<UnsupportedUniqueRuleException> {
                UniqueRuleEvaluator.evaluate(
                    ReverseReferenceIndex.build(listOf(node), gNodeDescriber),
                    brokenResolved,
                    UsedInRoleResolver(brokenResolved.values.toList(), "b"),
                )
            }.message!! shouldContain "nope"
        }

        // ------------------------------------------------------------------ nothing is dropped silently (G1)

        "a rule outside the supported subset is reported as Unsupported with its reason, not as null" {
            val outcome = WhereRuleValidator.prepareForInstanceGraph(WhereRuleSpec("WR9", "foo(SELF) = 1"))
            outcome.shouldBeInstanceOf<PreparationOutcome.Unsupported>()
            outcome.reason shouldContain "foo"
            WhereRuleValidator
                .prepareForInstanceGraph(WhereRuleSpec("WR1", "SIZEOF(USEDIN(SELF, '')) >= 0"))
                .shouldBeInstanceOf<PreparationOutcome.Prepared>()
        }

        "preparing a whole schema fails loudly naming entity, label and reason when one rule is unsupported" {
            val bad =
                ExpressSemanticModelBuilder
                    .build(
                        "SCHEMA b;\nENTITY e;\n  a : STRING;\nWHERE\n  WR7: unknown_fn(SELF) = 1;\nEND_ENTITY;\nEND_SCHEMA;",
                    ).schemas
                    .single()
            val badResolved = InheritanceResolver.resolve(bad)
            val message =
                shouldThrow<IllegalStateException> {
                    SchemaRulePreparer.prepareAll(badResolved.values, SchemaFunctionIntrinsicSet.AP242, "fixture")
                }.message!!
            message shouldContain "fixture"
            message shouldContain "e.WR7"
            message shouldContain "unknown_fn"
            // the supported schema above prepares fine, once per distinct inherited rule
            SchemaRulePreparer.prepareAll(resolved.values, SchemaFunctionIntrinsicSet.NONE, "ok").size shouldBe 2
        }

        "validating with an unsupported rule throws UnsupportedWhereExpressionException carrying the reason" {
            val self = GNode("base_e", mapOf("k" to str("x")))
            val index = ReverseReferenceIndex.build(listOf(self), gNodeDescriber)
            val context = InstanceEvaluationContext(self, "base_e", index, roleResolver)
            shouldThrow<UnsupportedWhereExpressionException> {
                WhereRuleValidator.validate(
                    "base_e",
                    listOf(WhereRuleSpec("WR9", "foo(SELF) = 1")),
                    self.values,
                    context,
                )
            }.message!! shouldContain "foo"
        }

        // ------------------------------------------------------------------ SELECT-aware INVERSE

        "an INVERSE whose FOR attribute is typed by a SELECT works when the SELECT admits the owner" {
            val owner = GNode("part_e")
            val link = GNode("link_e").ref("link_e", "target", owner)
            val index = ReverseReferenceIndex.build(listOf(owner, link), gNodeDescriber)
            val context = InstanceEvaluationContext(owner, "part_e", index, roleResolver)
            val inverse =
                schema.entities
                    .single { it.name == "part_e" }
                    .inverseAttributes
                    .single()
            val definedTypes = schema.definedTypes.associateBy { it.name.lowercase() }

            val evaluation = InverseAttributeEvaluator.evaluate("part_e", inverse, resolved, context, definedTypes)
            evaluation.value.size shouldBe 1
            evaluation.withinBounds shouldBe true

            // without the TYPE declarations the SELECT cannot be resolved: loud, as before ADR-0023
            shouldThrow<dev.kstep.express.semantic.SemanticModelException> {
                InverseAttributeEvaluator.evaluate("part_e", inverse, resolved, context)
            }
        }

        // ------------------------------------------------------------------ multi-hop chains

        "a chain narrows by type and answers EXPRESS ? (Unset) when the value is not of the narrowed type" {
            val part = GNode("part_e")
            val other = GNode("other_e", mapOf("v" to str("deep")))
            val viaPart = GNode("chain_e", mapOf("tag" to str("t1"))).ref("chain_e", "next", part)
            val viaOther = GNode("chain_e", mapOf("tag" to str("t2"))).ref("chain_e", "next", other)
            val unsetEnd = GNode("chain_e", mapOf("tag" to str("t3")))
            val chain =
                WhereRuleExpression.QualifiedChain(
                    listOf(
                        WhereRuleExpression.ChainLink("chain_e", "next"),
                        WhereRuleExpression.ChainLink("other_e", "v"),
                    ),
                )

            fun value(self: GNode): WhereRuleValue {
                val index = ReverseReferenceIndex.build(listOf(self), gNodeDescriber)
                val context = InstanceEvaluationContext(self, "chain_e", index, roleResolver)
                return WhereRuleEvaluator.evaluateToValue(chain, self.values, context)
            }
            value(viaOther) shouldBe str("deep")
            value(viaPart) shouldBe WhereRuleValue.Unset
            value(unsetEnd) shouldBe WhereRuleValue.Unset
        }

        "a chain whose last attribute is not a value attribute fails loudly" {
            val other = GNode("other_e", mapOf("v" to str("deep")))
            val self = GNode("chain_e", mapOf("tag" to str("t"))).ref("chain_e", "next", other)
            val index = ReverseReferenceIndex.build(listOf(self), gNodeDescriber)
            val context = InstanceEvaluationContext(self, "chain_e", index, roleResolver)
            val chain =
                WhereRuleExpression.QualifiedChain(
                    listOf(
                        WhereRuleExpression.ChainLink("chain_e", "next"),
                        WhereRuleExpression.ChainLink("other_e", "nope"),
                    ),
                )
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluateToValue(chain, self.values, context)
            }.message!! shouldContain "nope"
        }
    })
