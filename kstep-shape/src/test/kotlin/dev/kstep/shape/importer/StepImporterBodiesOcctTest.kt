package dev.kstep.shape.importer

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The per-body summary of [StepImporter.bodiesOf] and its cap: a part with more solids than the cap gets a
 * warning instead of failing the import. The class name carries `Occt` so the CI skip gate sees it.
 */
class StepImporterBodiesOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun square(x: Double): ProfileRegion {
            val p =
                listOf(
                    ProfilePoint(x, 0.0),
                    ProfilePoint(x + 10.0, 0.0),
                    ProfilePoint(x + 10.0, 10.0),
                    ProfilePoint(x, 10.0),
                )
            return ProfileRegion(ProfileLoop(p.indices.map { ProfileSegment.Line(p[it], p[(it + 1) % 4]) }))
        }

        fun twoBoxes() = OcctKernel.extrudeRegions(listOf(square(0.0), square(30.0)), 10.0)

        "a single-solid part has no bodies".config(enabled = available) {
            OcctKernel.makeBox(1.0, 2.0, 3.0).use {
                StepImporter.bodiesOf(it, "box", mutableListOf()).shouldBeNull()
            }
        }

        "a two-solid part reports both bodies".config(enabled = available) {
            twoBoxes().use { shape ->
                shape.topology.solids shouldBe 2
                val warnings = mutableListOf<String>()
                val bodies = StepImporter.bodiesOf(shape, "two", warnings).shouldNotBeNull()
                bodies.size shouldBe 2
                bodies.forEach { it.volume shouldBe (1000.0 plusOrMinus 1e-6) }
                warnings shouldBe emptyList()
            }
        }

        "more solids than the cap omit the bodies with a warning instead of failing".config(enabled = available) {
            twoBoxes().use { shape ->
                val warnings = mutableListOf<String>()
                StepImporter.bodiesOf(shape, "many", warnings, limit = 1).shouldBeNull()
                warnings.size shouldBe 1
                warnings[0] shouldContain "bodies omitted"
                warnings[0] shouldContain "2 solids exceed 1"
            }
        }

        "the default cap mirrors the native limit".config(enabled = available) {
            StepImporter.MAX_BODY_SUMMARIES shouldBe 1024
        }
    })
