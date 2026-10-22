package dev.kstep.tests

import dev.kstep.express.validation.ResolvedUsedInRole
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleEvaluationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class UsedInRoleResolverTest :
    StringSpec({
        val resolver = usedInTestRoleResolver

        "a modelled role resolves to the declaring entity and attribute" {
            resolver.resolve("usedin_test.base_item.parent") shouldBe ResolvedUsedInRole.Modelled("base_item", "parent")
        }

        "resolution is case-insensitive and normalises to lower case" {
            resolver.resolve("USEDIN_TEST.BASE_ITEM.PARENT") shouldBe ResolvedUsedInRole.Modelled("base_item", "parent")
        }

        "the schema alias is accepted" {
            resolver.resolve("ALIAS_SCHEMA.BASE_ITEM.PARENT") shouldBe
                ResolvedUsedInRole.Modelled("base_item", "parent")
        }

        "an inherited attribute named through the subtype is rejected with a hint to the declaring entity" {
            val e = shouldThrow<WhereRuleEvaluationException> { resolver.resolve("usedin_test.item.parent") }
            e.message shouldContain "declared by 'base_item'"
        }

        "an explicitly listed unmodelled role yields KnownUnmodelled" {
            val resolved = resolver.resolve("ALIAS_SCHEMA.GHOST.THING")
            (resolved as ResolvedUsedInRole.KnownUnmodelled).reason shouldContain "ghost"
        }

        "the empty role string means any role" {
            resolver.resolve("") shouldBe ResolvedUsedInRole.AnyRole
        }

        "an unknown schema is a loud error" {
            shouldThrow<WhereRuleEvaluationException> {
                resolver.resolve(
                    "other.base_item.parent",
                )
            }.message shouldContain
                "unknown schema"
        }

        "an unknown entity that is not on the unmodelled list is a loud error" {
            shouldThrow<WhereRuleEvaluationException> { resolver.resolve("usedin_test.phantom.thing") }
        }

        "a typo in an unmodelled role never silently yields an empty set" {
            shouldThrow<WhereRuleEvaluationException> { resolver.resolve("ALIAS_SCHEMA.GHOST.THNG") }
        }

        "a modelled entity without the attribute is a loud error" {
            shouldThrow<WhereRuleEvaluationException> {
                resolver.resolve(
                    "usedin_test.base_item.nope",
                )
            }.message shouldContain
                "no attribute"
        }

        "wrong segment counts are rejected" {
            shouldThrow<WhereRuleEvaluationException> { resolver.resolve("usedin_test.base_item") }
            shouldThrow<WhereRuleEvaluationException> { resolver.resolve("usedin_test.base_item.parent.more") }
            shouldThrow<WhereRuleEvaluationException> { resolver.resolve("usedin_test..parent") }
        }

        "an overlong role text is rejected and not echoed in full" {
            val e = shouldThrow<WhereRuleEvaluationException> { resolver.resolve("a".repeat(600)) }
            (e.message!!.length < 200) shouldBe true
        }

        "a known unmodelled role of a foreign schema is rejected at construction" {
            shouldThrow<IllegalArgumentException> {
                UsedInRoleResolver(emptyList(), "s", emptySet(), mapOf("OTHER.X.Y" to "r"))
            }
        }
    })
