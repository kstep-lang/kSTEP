package dev.kstep.desktop.pick

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AssemblyTreeTest :
    StringSpec({
        /** (name, parent, part) triples in pre-order. */
        fun tree(vararg n: Triple<String, Int, Int>) =
            AssemblyTree(
                Array(n.size) { n[it].first },
                IntArray(n.size) { n[it].second },
                IntArray(n.size) { n[it].third },
            )

        // 0 Top
        //   1 Sub        2 X [0]   3 X [1]   4 Y [2]
        //   5 Sub        6 X [3]
        //   7 Z [4]
        val t =
            tree(
                Triple("Top", -1, -1),
                Triple("Sub", 0, -1),
                Triple("X", 1, 0),
                Triple("X", 1, 1),
                Triple("Y", 1, 2),
                Triple("Sub", 0, -1),
                Triple("X", 5, 3),
                Triple("Z", 0, 4),
            )

        "subtree ends are exclusive pre-order bounds" {
            t.subtreeEnd(0) shouldBe 8
            t.subtreeEnd(1) shouldBe 5
            t.subtreeEnd(5) shouldBe 7
            t.subtreeEnd(2) shouldBe 3
            t.subtreeEnd(7) shouldBe 8
        }

        "depth, groups and leaves" {
            t.depth(0) shouldBe 0
            t.depth(6) shouldBe 2
            t.isGroup(1) shouldBe true
            t.isGroup(2) shouldBe false
            t.partOf(4) shouldBe 2
            t.partOf(1) shouldBe -1
        }

        "first and last part and the leaf count of a group" {
            t.firstPart(0) shouldBe 0
            t.lastPart(0) shouldBe 4
            t.firstPart(1) shouldBe 0
            t.lastPart(1) shouldBe 2
            t.firstPart(5) shouldBe 3
            t.lastPart(5) shouldBe 3
            t.leafCount(0) shouldBe 5
            t.leafCount(1) shouldBe 3
            t.leafCount(2) shouldBe 1
        }

        "leafNodeOfPart finds the leaf of each part and -1 for an unknown one" {
            (0..4).map { t.leafNodeOfPart(it) } shouldBe listOf(2, 3, 4, 6, 7)
            t.leafNodeOfPart(5) shouldBe -1
            t.leafNodeOfPart(-1) shouldBe -1
        }

        "occurrence counts among same-named siblings only" {
            t.occurrence(1) shouldBe 0
            t.occurrence(5) shouldBe 1
            t.occurrence(2) shouldBe 0
            t.occurrence(3) shouldBe 1
            t.occurrence(6) shouldBe 0 // the only X below the second Sub
            t.hasDuplicateSiblingName(1) shouldBe true
            t.hasDuplicateSiblingName(6) shouldBe false
            t.hasDuplicateSiblingName(7) shouldBe false
            t.duplicateSuffix(5) shouldBe " #2"
            t.duplicateSuffix(7) shouldBe ""
        }

        "keys are unique and map back to their node" {
            val keys = (0 until t.size).map { t.key(it) }
            keys.toSet().size shouldBe t.size
            for (i in 0 until t.size) t.nodeOfKey(t.key(i)) shouldBe i
            t.nodeOfKey("nope") shouldBe -1
        }

        "names with slash and hash cannot collide, because the length is part of the key" {
            val a = tree(Triple("R", -1, -1), Triple("a/1:b", 0, -1), Triple("c", 1, 0))
            val b = tree(Triple("R", -1, -1), Triple("a", 0, -1), Triple("1:b/c", 1, 0))
            a.key(2) shouldNotBe b.key(2)
            val c = tree(Triple("R", -1, -1), Triple("x#1", 0, 0), Triple("x", 0, 1), Triple("x", 0, 2))
            c.key(1) shouldNotBe c.key(3)
            c.key(2) shouldNotBe c.key(3)
        }

        "a key stays the same when an earlier sibling is removed from another branch" {
            val key = t.key(6)
            val smaller =
                tree(
                    Triple("Top", -1, -1),
                    Triple("Sub", 0, -1),
                    Triple("X", 1, 0),
                    Triple("X", 1, 1),
                    Triple("Sub", 0, -1),
                    Triple("X", 4, 2),
                )
            smaller.key(5) shouldBe key
        }

        "keys stay short however deep and long the names are" {
            val names = Array(65) { "n".repeat(256) }
            val parent = IntArray(65) { it - 1 }
            val partOf = IntArray(65) { -1 }.also { it[64] = 0 }
            val deep = AssemblyTree(names, parent, partOf)
            (deep.key(64).length <= 64) shouldBe true
        }

        "displayPath joins the names and marks duplicates" {
            t.displayPath(6) shouldBe "Top / Sub #2 / X"
            t.displayPath(3) shouldBe "Top / Sub #1 / X #2"
            t.displayPath(0) shouldBe "Top"
        }
    })
