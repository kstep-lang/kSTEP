package dev.kstep.desktop.recent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** The Recent model (ADR-0066): newest first, at most ten, one entry per path. */
class RecentListTest :
    StringSpec({
        fun script(name: String) = RecentEntry(Path.of("/w/$name.kstep.kts"), RecentKind.SCRIPT)

        "the eleventh entry pushes out the oldest" {
            var list = RecentList.empty(false)
            (1..11).forEach { list = list.touch(script("s$it")) }
            list.entries.size shouldBe 10
            list.entries.first() shouldBe script("s11")
            list.entries.last() shouldBe script("s2")
        }

        "touching an entry again moves it to the front without a duplicate" {
            val list =
                RecentList
                    .empty(false)
                    .touch(script("a"))
                    .touch(script("b"))
                    .touch(script("a"))
            list.entries shouldBe listOf(script("a"), script("b"))
        }

        "paths that differ only by dot segments are one entry" {
            val list =
                RecentList
                    .empty(false)
                    .touch(RecentEntry(Path.of("/w/a/../a/x.kstep.kts"), RecentKind.SCRIPT))
                    .touch(script("a").copy(path = Path.of("/w/a/x.kstep.kts")))
            list.entries.size shouldBe 1
        }

        "case counts on a case-sensitive file system and not on a case-insensitive one" {
            val upper = RecentEntry(Path.of("/w/A.kstep.kts"), RecentKind.SCRIPT)
            val lower = RecentEntry(Path.of("/w/a.kstep.kts"), RecentKind.SCRIPT)
            RecentList
                .empty(false)
                .touch(upper)
                .touch(lower)
                .entries.size shouldBe 2
            RecentList
                .empty(true)
                .touch(upper)
                .touch(lower)
                .entries shouldBe listOf(lower)
        }

        "a folder and a script with the same path are one entry" {
            val path = Path.of("/w/x")
            val list =
                RecentList
                    .empty(false)
                    .touch(RecentEntry(path, RecentKind.SCRIPT))
                    .touch(RecentEntry(path, RecentKind.FOLDER))
            list.entries shouldBe listOf(RecentEntry(path, RecentKind.FOLDER))
        }

        "remove and clear" {
            val list = RecentList.empty(false).touch(script("a")).touch(script("b"))
            list.remove(Path.of("/w/a.kstep.kts")).entries shouldBe listOf(script("b"))
            list.remove(Path.of("/nowhere")).entries.size shouldBe 2
            list.clear().entries shouldBe emptyList()
        }

        "of() drops duplicates and cuts at ten" {
            val many = (1..15).map { script("s$it") } + script("s1")
            val list = RecentList.of(many, false)
            list.entries.size shouldBe 10
            list.entries.first() shouldBe script("s1")
        }
    })
