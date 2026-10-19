package dev.kstep.tests

import dev.kstep.express.codegen.NamingConventions
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.validation.DerivedAttributeEvaluator
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.tests.generated.derive.ShapeInfo
import dev.kstep.tests.generated.derive.SpecialInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Runtime parity (ADR-0021): the generated DERIVE properties (`generateDeriveFixtureKotlin`,
 * compiled into this test source set) must agree with the reflective
 * [DerivedAttributeEvaluator] for a grid of inputs, including `-0.0`, `NaN`, infinities, empty
 * strings and unset optionals -- the cases where a naive `==`/`<` translation would diverge.
 */
private val fixtureSchema =
    ExpressSemanticModelBuilder
        .build(File("src/test/express/derive-codegen-fixture.exp").readText())
        .schemas
        .single()
private val resolvedFixture = InheritanceResolver.resolve(fixtureSchema)

private val titles = listOf("", "x", "a", "z")
private val counts = listOf(-1, 0, 1, 10, 11)
private val weights = listOf(-0.0, 0.0, 1.0, 2.5, 2.4999, -2.0, Double.NaN, Double.POSITIVE_INFINITY)
private val flags = listOf(true, false)
private val notes = listOf(null, "", "n")

private fun stringOf(value: Any?): WhereRuleValue =
    when (value) {
        null -> WhereRuleValue.Unset
        is String -> WhereRuleValue.StringValue(value)
        is Int -> WhereRuleValue.IntegerValue(value.toLong())
        is Double -> WhereRuleValue.RealValue(value)
        is Boolean -> WhereRuleValue.BooleanValue(value)
        else -> error("unexpected value $value")
    }

/** Evaluates every DERIVE of [entity] with the evaluator, in dependency order, against [explicit]. */
private fun evaluateAll(
    entity: ResolvedEntity,
    explicit: Map<String, Any?>,
): Map<String, WhereRuleValue> {
    val bag = explicit.mapValues { stringOf(it.value) }.toMutableMap()
    val results = LinkedHashMap<String, WhereRuleValue>()
    val pending = entity.flattenedDerivedAttributes.toMutableList()
    var guard = 0
    while (pending.isNotEmpty()) {
        check(guard++ < 100) { "derived attributes did not resolve: ${pending.map { it.name }}" }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val attr = iterator.next()
            val value =
                try {
                    DerivedAttributeEvaluator.evaluate(attr.expressionText, bag)
                } catch (e: WhereRuleEvaluationException) {
                    when {
                        e.message!!.contains("is Unset") -> WhereRuleValue.Unset
                        e.message!!.contains("not present") -> continue
                        else -> throw e
                    }
                }
            bag[attr.name] = value
            results[attr.name] = value
            iterator.remove()
        }
    }
    return results
}

private fun checkParity(
    instance: Any,
    entity: ResolvedEntity,
    explicit: Map<String, Any?>,
) {
    val expected = evaluateAll(entity, explicit)
    entity.flattenedDerivedAttributes.size shouldBe expected.size
    entity.flattenedDerivedAttributes.forEach { attr ->
        val property =
            instance::class.memberProperties.single { it.name == NamingConventions.toPropertyName(attr.name) }
        val actual = property.getter.call(instance)
        val reference = expected.getValue(attr.name)
        val description = "${instance::class.simpleName}.${attr.name} for $explicit"
        val comparable: Any? =
            when (reference) {
                WhereRuleValue.Unset -> null
                is WhereRuleValue.StringValue -> reference.value
                is WhereRuleValue.BooleanValue -> reference.value
                is WhereRuleValue.RealValue -> reference.value
                // INTEGER initializer of a REAL attribute: the generated code promotes, the evaluator does not.
                is WhereRuleValue.IntegerValue ->
                    if (actual is Double) {
                        reference.value.toDouble()
                    } else {
                        reference.value
                            .toInt()
                    }
            }
        withClue(description) { actual shouldBe comparable }
    }
}

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T = io.kotest.assertions.withClue(clue, block)

class DerivedAttributeRuntimeParityTest :
    StringSpec({
        "every generated derived property of ShapeInfo equals the evaluator over a grid with -0.0, NaN and unsets" {
            val entity = resolvedFixture.getValue("shape_info")
            var checked = 0
            for (title in titles) {
                for (count in counts) {
                    for (weight in weights) {
                        for (flag in flags) {
                            for (note in notes) {
                                for (pinned in listOf(0, 6)) {
                                    val instance = ShapeInfo(title, count, weight, flag, note, pinned)
                                    checkParity(
                                        instance,
                                        entity,
                                        mapOf(
                                            "title" to title,
                                            "count" to count,
                                            "weight" to weight,
                                            "flag" to flag,
                                            "note" to note,
                                            "pinned" to pinned,
                                        ),
                                    )
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
            checked shouldBe titles.size * counts.size * weights.size * flags.size * notes.size * 2
        }

        "SpecialInfo overrides one DERIVE and redeclares an explicit attribute as DERIVE, matching the evaluator" {
            val entity = resolvedFixture.getValue("special_info")
            for (count in counts) {
                for (weight in weights) {
                    for (note in notes) {
                        for (extra in listOf(0, 6, 8)) {
                            val instance = SpecialInfo("x", count, weight, true, note, extra)
                            checkParity(
                                instance,
                                entity,
                                // `pinned` is no longer an explicit value: its DERIVE (7) feeds the others.
                                mapOf(
                                    "title" to "x",
                                    "count" to count,
                                    "weight" to weight,
                                    "flag" to true,
                                    "note" to note,
                                    "extra" to extra,
                                ),
                            )
                        }
                    }
                }
            }
            val sample = SpecialInfo("x", 1, 1.0, true, null, 8)
            sample.titleAlias shouldBe "overridden"
            sample.pinned shouldBe 7
            sample.extraBig shouldBe true
        }

        "the redeclared explicit attribute is not a constructor parameter of the subtype, but of the supertype" {
            SpecialInfo::class.primaryConstructor!!.parameters.map { it.name } shouldNotContain "pinned"
            ShapeInfo::class.primaryConstructor!!.parameters.map { it.name } shouldContain "pinned"
        }

        "derived values are not part of equals, hashCode, toString or copy, and copy recomputes them" {
            val a = ShapeInfo("x", 1, 1.0, true, null, 0)
            val b = ShapeInfo("x", 1, 1.0, true, null, 0)
            (a == b) shouldBe true
            (a.hashCode() == b.hashCode()) shouldBe true
            a.toString() shouldNotContain "isBig"
            a.toString() shouldNotContain "chainA"
            a.isBig shouldBe false
            a.copy(count = 20).isBig shouldBe true
            a.copy(count = 20).chainB shouldBe false
        }

        "the escaped string literal evaluates to its exact text" {
            ShapeInfo("x", 1, 1.0, true, null, 0).escapedText shouldBe "cost \$5 \"q\" \\ \${System.exit(1)} \"\"\""
        }
    })
