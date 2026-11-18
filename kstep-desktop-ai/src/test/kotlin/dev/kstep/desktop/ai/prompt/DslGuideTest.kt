package dev.kstep.desktop.ai.prompt

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class DslGuideTest :
    FunSpec({
        test("the guide is at most 24 KiB") {
            (DslGuide.text.toByteArray().size <= 24 * 1024) shouldBe true
        }
        test("every embedded example is byte-identical to its fixture in kstep-tests") {
            val dir = Path.of(System.getProperty("kstep.tests.resources"))
            val re =
                Regex(
                    "<!-- example: (\\S+) -->\n```kotlin\n(.*?)\n```\n<!-- end example -->",
                    RegexOption.DOT_MATCHES_ALL,
                )
            val found = re.findAll(DslGuide.text).toList()
            (found.size >= 3) shouldBe true
            for (m in found) {
                val fixture = Files.readString(dir.resolve(m.groupValues[1]))
                m.groupValues[2].trimEnd() shouldBe fixture.trimEnd()
            }
        }
    })
