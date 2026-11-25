package dev.kstep.desktop.run

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** The note of the packaged JVM-only app (ADR-0082). */
class PackagedHintTest :
    StringSpec({
        "the packaged hint names the JVM-only build and does not start with a Gradle command" {
            val lines = PackagedHint.lines()
            lines.first() shouldContain "ADR-0082"
            lines.first() shouldNotContain "gradlew"
            lines.first() shouldNotContain "-Pkstep"
        }

        "only a packaged location counts as packaged" {
            val packaged = WorkerLocation("/j", "/c", WorkerLocation.Source.PACKAGED)
            val dev = WorkerLocation("/j", "/c", WorkerLocation.Source.GRADLE)
            PackagedHint.active(WorkerLocationResult.Found(packaged)) shouldBe true
            PackagedHint.active(WorkerLocationResult.Found(dev)) shouldBe false
            PackagedHint.active(WorkerLocationResult.Broken("KSTEP-D-038", "x")) shouldBe false
        }
    })
