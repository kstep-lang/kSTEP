package dev.kstep.tests

import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.SemanticModelException
import dev.kstep.express.validation.AggregateKind
import dev.kstep.express.validation.InverseAttributeEvaluator
import dev.kstep.express.validation.InverseBounds
import dev.kstep.express.validation.UnsupportedInverseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun inverse(name: String): ExpressInverseAttribute =
    usedInTestResolved
        .getValue("owner_e")
        .entity.inverseAttributes
        .first { it is ExpressInverseAttribute.Explicit && it.name == name }

private fun evaluate(
    name: String,
    owner: GNode,
    vararg others: GNode,
) = InverseAttributeEvaluator.evaluate("owner_e", inverse(name), usedInTestResolved, usedInContext(owner, *others))

private fun itemOf(owner: GNode) = GNode("item").ref("base_item", "parent", owner)

class InverseAttributeEvaluatorTest :
    StringSpec({
        "bounds parse for the supported spellings" {
            InverseAttributeEvaluator.parseBounds(inverse("items")) shouldBe InverseBounds(1, null)
            InverseAttributeEvaluator.parseBounds(inverse("exact_two")) shouldBe InverseBounds(2, 2)
            InverseAttributeEvaluator.parseBounds(inverse("open_set")) shouldBe InverseBounds(0, null)
            InverseAttributeEvaluator.parseBounds(inverse("single")) shouldBe InverseBounds(1, 1)
            InverseAttributeEvaluator.parseBounds(inverse("all_bag")) shouldBe InverseBounds(0, null)
        }

        "malformed, negative, symbolic and overflowing bounds are unsupported" {
            for (name in listOf("bad_lower_upper", "negative", "symbolic", "huge")) {
                shouldThrow<UnsupportedInverseException> { InverseAttributeEvaluator.parseBounds(inverse(name)) }
            }
        }

        "[1:?] with no referrer is out of bounds and the message names the link" {
            val owner = GNode("owner_e")
            val result = evaluate("items", owner)
            result.withinBounds shouldBe false
            result.value.size shouldBe 0
            result.failedLink shouldBe
                "owner_e.items <- base_item.parent: found 0, required [1:?]"
        }

        "[1:?] with one referrer is within bounds" {
            val owner = GNode("owner_e")
            evaluate("items", owner, itemOf(owner)).withinBounds shouldBe true
        }

        "[2:2] accepts exactly two" {
            val owner = GNode("owner_e")
            evaluate("exact_two", owner, itemOf(owner)).withinBounds shouldBe false
            evaluate("exact_two", owner, itemOf(owner), itemOf(owner)).withinBounds shouldBe true
            evaluate("exact_two", owner, itemOf(owner), itemOf(owner), itemOf(owner)).withinBounds shouldBe false
        }

        "an inverse without an aggregation means exactly one referrer, with FOR entity.attribute resolved" {
            val owner = GNode("owner_e")
            evaluate("single", owner).withinBounds shouldBe false
            evaluate("single", owner, itemOf(owner)).withinBounds shouldBe true
            evaluate("single", owner, itemOf(owner), itemOf(owner)).withinBounds shouldBe false
        }

        "a SET counts distinct referrers while a BAG counts every reference" {
            val owner = GNode("owner_e")
            val twice = GNode("item").ref("base_item", "parent", owner).ref("base_item", "parent", owner)
            val set = evaluate("open_set", owner, twice)
            set.value.kind shouldBe AggregateKind.SET
            set.value.size shouldBe 1
            val bag = evaluate("all_bag", owner, twice)
            bag.value.kind shouldBe AggregateKind.BAG
            bag.value.size shouldBe 2
        }

        "a subtype of the target entity counts, a sibling of a different entity does not" {
            val owner = GNode("owner_e")
            val item = itemOf(owner)
            val base = GNode("base_item").ref("base_item", "parent", owner)
            val other = GNode("other_item").ref("base_item", "parent", owner)
            evaluate("open_set", owner, item, base, other).value.size shouldBe 1
            evaluate("base_set", owner, item, base, other).value.size shouldBe 2
        }

        "a same-named attribute declared by a foreign entity does not count" {
            val owner = GNode("owner_e")
            val foreign = GNode("item").ref("other_item", "parent", owner)
            evaluate("open_set", owner, foreign).value.size shouldBe 0
        }

        "a redeclared inverse is unsupported" {
            val redeclared =
                ExpressInverseAttribute.Redeclared(
                    rawText = "SELF\\a.b",
                    kind = null,
                    boundsRawText = null,
                    targetEntity = "item",
                    forEntity = null,
                    forAttribute = "parent",
                    sourceLine = 1,
                )
            val owner = GNode("owner_e")
            shouldThrow<UnsupportedInverseException> {
                InverseAttributeEvaluator.evaluate("owner_e", redeclared, usedInTestResolved, usedInContext(owner))
            }
        }

        "static schema problems are loud errors" {
            val owner = GNode("owner_e")
            shouldThrow<SemanticModelException> { evaluate("wrong_target", owner) }.message shouldContain
                "does not reference"
            shouldThrow<SemanticModelException> { evaluate("missing_attr", owner) }.message shouldContain "no attribute"
        }
    })
