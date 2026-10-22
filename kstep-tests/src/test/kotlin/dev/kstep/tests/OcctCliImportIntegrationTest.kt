package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Subprocess coverage of `kstep import` on real geometry (ADR-0030). The class name carries `Occt` so
 * the CI skip gate sees these tests.
 */
class OcctCliImportIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-import-occt").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyResource(
            resource: String,
            target: String = resource.substringAfterLast('/'),
        ): File {
            val file = File(workDir, target)
            val input =
                requireNotNull(OcctCliImportIntegrationTest::class.java.getResourceAsStream("/$resource")) { resource }
            input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            return file
        }

        fun exportPlaced(): File {
            val script = copyResource("export-placed-assembly.kstep.kts")
            val out = File(workDir, "placed.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            return out
        }

        fun JsonObject.box(): Pair<List<Double>, List<Double>> =
            this["min"]!!.jsonArray.map { it.jsonPrimitive.double } to
                this["max"]!!.jsonArray.map { it.jsonPrimitive.double }

        "export then import --output json prints exactly one JSON object with the exact numbers".config(
            enabled = available,
        ) {
            val step = exportPlaced()
            val result = runner.run("import", step.name, "--output", "json")
            result.exitCode shouldBe 0
            // stdout holds exactly one parsable document: proves OCCT's own console output is silenced.
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "success"
            json["structureSource"]!!.jsonPrimitive.content shouldBe "xcaf"
            json["lengthUnit"]!!.jsonPrimitive.content shouldBe "mm"
            json["unknownEntities"]!!.jsonObject["count"]!!.jsonPrimitive.int shouldBe 0
            val parts = json["parts"]!!.jsonArray
            parts.size shouldBe 3
            val volumes =
                parts.associate {
                    it.jsonObject["name"]!!.jsonPrimitive.content to
                        it.jsonObject["volume"]!!.jsonPrimitive.double
                }
            volumes.getValue("Gehaeuse") shouldBe (16000.0 plusOrMinus 1e-3)
            volumes.getValue("Halter") shouldBe (1000.0 plusOrMinus 1e-3)
            volumes.getValue("Schraube") shouldBe (192.0 plusOrMinus 1e-3)
            val totals = json["totals"]!!.jsonObject
            totals["occurrenceCount"]!!.jsonPrimitive.int shouldBe 4
            totals["volume"]!!.jsonPrimitive.double shouldBe (17384.0 plusOrMinus 1e-3)
            val (min, max) = totals["boundingBox"]!!.jsonObject.box()
            min.zip(listOf(0.0, 0.0, 0.0)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-4) }
            max.zip(listOf(40.0, 40.0, 22.0)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-4) }
            val occurrences = json["occurrences"]!!.jsonArray
            val halter =
                occurrences
                    .single {
                        it.jsonObject["path"]!!.jsonArray.map { p -> p.jsonPrimitive.content } ==
                            listOf("NAUO-B")
                    }.jsonObject
            val (hMin, hMax) = halter["boundingBox"]!!.jsonObject.box()
            hMin.zip(listOf(10.0, 5.0, 10.0)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-4) }
            hMax.zip(listOf(30.0, 15.0, 15.0)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-4) }
            halter["placement"]!!.jsonObject["rigid"]!!.jsonPrimitive.boolean shouldBe true
            (json["warnings"] is JsonArray) shouldBe true
        }

        "the text report lists structure, parts, occurrences and totals".config(enabled = available) {
            val step = exportPlaced()
            val result = runner.run("import", step.name)
            result.exitCode shouldBe 0
            result.stdout shouldContain "Imported placed.step"
            result.stdout shouldContain "NAUO-B -> Halter [BRK-001]"
            result.stdout shouldContain "t=(30,5,10) Rz90"
            result.stdout shouldContain "Total: 4 occurrences, volume 17384.000 mm3"
        }

        "import never creates a file next to the input".config(enabled = available) {
            val step = exportPlaced()
            val before = workDir.list()!!.toSet()
            runner.run("import", step.name, "--output", "json").exitCode shouldBe 0
            workDir.list()!!.toSet() shouldBe before
        }

        "--render writes svg, png and glb only where asked".config(enabled = available) {
            val step = exportPlaced()
            for (ext in listOf("svg", "png", "glb")) {
                val out = File(workDir, "imported.$ext").apply { delete() }
                val result = runner.run("import", step.name, "--render", out.name, "--output", "json")
                result.exitCode shouldBe 0
                out.isFile shouldBe true
                val render = Json.parseToJsonElement(result.stdout.trim()).jsonObject["render"]!!.jsonObject
                render["format"]!!.jsonPrimitive.content shouldBe ext
                (render["triangleCount"]!!.jsonPrimitive.int > 0) shouldBe true
                val bytes = out.readBytes()
                when (ext) {
                    "svg" -> String(bytes, Charsets.UTF_8) shouldContain "<svg"
                    "png" ->
                        bytes.take(8) shouldBe
                            listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
                    "glb" -> {
                        String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) shouldBe "glTF"
                        java.nio.ByteBuffer
                            .wrap(bytes, 8, 4)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .int shouldBe
                            bytes.size
                    }
                }
            }
        }

        "--validate on a kSTEP export reports no findings and exit 0".config(enabled = available) {
            val step = exportPlaced()
            val result = runner.run("import", step.name, "--validate", "--output", "json")
            result.exitCode shouldBe 0
            val validation = Json.parseToJsonElement(result.stdout.trim()).jsonObject["validation"]!!.jsonObject
            validation["available"]!!.jsonPrimitive.boolean shouldBe true
            validation["ok"]!!.jsonPrimitive.boolean shouldBe true
            validation["violations"]!!.jsonPrimitive.int shouldBe 0
        }

        "a hand-written fixture with an unknown entity imports with a warning".config(enabled = available) {
            copyResource("import/unknown-entities.step")
            val result = runner.run("import", "unknown-entities.step", "--output", "json")
            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            (json["unknownEntities"]!!.jsonObject["count"]!!.jsonPrimitive.int >= 1) shouldBe true
            json["unknownEntities"]!!.jsonObject["types"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("KSTEP_UNKNOWN_THING")
        }
    })
