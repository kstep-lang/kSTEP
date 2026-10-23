package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** A native "limit:" failure surfaces as [OcctImportLimitException] (needs the OCCT bridge). */
class OcctImportLimitMappingTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a tiny entity limit becomes OcctImportLimitException".config(enabled = available) {
            val dir = File("build/limit-mapping").apply { mkdirs() }
            val file = File(dir, "box.step")
            OcctKernel.makeBox(1.0, 2.0, 3.0).use { it.writeStepFile(file.toPath()) }
            val limits = intArrayOf(10, 10_000, 10_000, 2_000, 250_000, 256)
            shouldThrow<OcctImportLimitException> { OcctKernel.readStep(file.readBytes(), limits) }
        }

        "empty content is an IllegalArgumentException before any native call".config(enabled = available) {
            shouldThrow<IllegalArgumentException> { OcctKernel.readStep(ByteArray(0)) }
        }

        // The placed-assembly fixture (`kstep export export-placed-assembly.kstep.kts`, see README.adoc in
        // kstep-tests' import fixtures) has: 8 XCAF nodes (1 assembly + 3 parts + 4 components), 4 components,
        // 3 parts, 18 faces in total (three boxes) and the root assembly name "Baugruppe".
        fun placedAssembly(): ByteArray =
            requireNotNull(OcctImportLimitMappingTest::class.java.getResourceAsStream("/import/placed-assembly.step")) {
                "placed-assembly.step"
            }.use { it.readBytes() }

        // Index order of the native limits array: entities, nodes, components, parts, faces, name chars.
        fun limits(
            nodes: Int = 10_000,
            components: Int = 10_000,
            parts: Int = 2_000,
            faces: Int = 250_000,
            nameChars: Int = 256,
        ) = intArrayOf(1_000_000, nodes, components, parts, faces, nameChars)

        fun limitMessage(limits: IntArray): String =
            shouldThrow<OcctImportLimitException> { OcctKernel.readStep(placedAssembly(), limits) }.message.orEmpty()

        "StepImportLimits.nativeLimits keeps the index order the native code reads" {
            StepImportLimits.nativeLimits().toList() shouldBe
                listOf(
                    StepImportLimits.MAX_ENTITIES,
                    StepImportLimits.MAX_NODES,
                    StepImportLimits.MAX_COMPONENTS,
                    StepImportLimits.MAX_PARTS,
                    StepImportLimits.MAX_TOTAL_FACES,
                    StepImportLimits.MAX_NAME_CHARS,
                )
        }

        "limits exactly at the fixture's size are accepted (no off-by-one)".config(enabled = available) {
            OcctKernel
                .readStep(placedAssembly(), limits(nodes = 8, components = 4, parts = 3, faces = 18))
                .use { doc ->
                    doc.nodes.size shouldBe 8
                }
        }

        "a part limit below the part count is a part-count limit failure".config(enabled = available) {
            limitMessage(limits(parts = 1)) shouldContain "limit: part count"
            limitMessage(limits(parts = 2)) shouldContain "limit: part count"
        }

        "a component limit below the component count is a component-count limit failure".config(enabled = available) {
            limitMessage(limits(components = 1)) shouldContain "limit: component count"
            limitMessage(limits(components = 3)) shouldContain "limit: component count"
        }

        "a face limit below the summed face count is a face-count limit failure".config(enabled = available) {
            limitMessage(limits(faces = 5)) shouldContain "limit: face count"
            limitMessage(limits(faces = 17)) shouldContain "limit: face count"
        }

        "a node limit below the node count is a node-count limit failure".config(enabled = available) {
            limitMessage(limits(nodes = 2)) shouldContain "limit: node count"
            limitMessage(limits(nodes = 7)) shouldContain "limit: node count"
        }

        "names are truncated to the name limit, not rejected".config(enabled = available) {
            OcctKernel.readStep(placedAssembly(), limits(nameChars = 3)).use { doc ->
                doc.nodes.all { it.name.length <= 3 } shouldBe true
                doc.nodes.any { it.name == "Bau" } shouldBe true
            }
        }

        "a length unit that cannot be resolved becomes OcctUnitException, before any geometry is transferred".config(
            enabled = available,
        ) {
            // kSTEP writes `SI_UNIT(.MILLI.,.METRE.)` units; replace every one with a conversion unit of factor 0.
            val text = String(placedAssembly(), Charsets.ISO_8859_1)
            val marker = Regex("""#(\d+)=\(LENGTH_UNIT\(\) NAMED_UNIT\(\*\) SI_UNIT\(\.MILLI\.,\.METRE\.\)\);""")
            val patched =
                marker
                    .replace(text) {
                        "#${it.groupValues[1]}=(CONVERSION_BASED_UNIT('X',#90001) LENGTH_UNIT() NAMED_UNIT(#90002));"
                    }.replace(
                        "ENDSEC;\nEND-ISO-10303-21;",
                        "#90001=LENGTH_MEASURE_WITH_UNIT(LENGTH_MEASURE(0.),#90003);\n" +
                            "#90002=DIMENSIONAL_EXPONENTS(1.,0.,0.,0.,0.,0.,0.);\n" +
                            "#90003=(LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT(.MILLI.,.METRE.));\n" +
                            "ENDSEC;\nEND-ISO-10303-21;",
                    )
            shouldThrow<OcctUnitException> { OcctKernel.readStep(patched.toByteArray(Charsets.ISO_8859_1)) }
        }

        fun withStyleRecords(
            records: (Int) -> String,
            count: Int,
        ): ByteArray {
            val text = String(placedAssembly(), Charsets.ISO_8859_1)
            val extra = (0 until count).joinToString("") { records(it) }
            return text
                .replace("ENDSEC;\nEND-ISO-10303-21;", extra + "ENDSEC;\nEND-ISO-10303-21;")
                .toByteArray(Charsets.ISO_8859_1)
        }

        "a hostile number of layer assignments is rejected before the transfer, quickly".config(enabled = available) {
            val n = StepImportLimits.MAX_LAYER_ASSIGNMENTS + 1
            val bytes = withStyleRecords({ "#${100000 + it}=PRESENTATION_LAYER_ASSIGNMENT('L$it','',(#34));\n" }, n)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: layer assignment count"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "a hostile number of distinct colours is rejected before the transfer, quickly".config(enabled = available) {
            val n = StepImportLimits.MAX_COLOUR_DEFINITIONS + 1
            val bytes = withStyleRecords({ "#${100000 + it}=COLOUR_RGB('',${it % 100 / 100.0},0.,0.);\n" }, n)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: colour definition count"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "a hostile number of transparency definitions is rejected before the transfer, quickly".config(
            enabled = available,
        ) {
            val n = StepImportLimits.MAX_TRANSPARENCY_DEFINITIONS + 1
            val bytes = withStyleRecords({ "#${100000 + it}=SURFACE_STYLE_TRANSPARENT(${it % 100 / 100.0});\n" }, n)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: colour definition count"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "distinct colour x transparency pairs through rendering records are rejected, quickly".config(
            enabled = available,
        ) {
            // 2000 colours x many transparencies, each pair in its own rendering record (the DoS shape of the review).
            val colours = 2000
            val n = StepImportLimits.MAX_RENDERING_PROPERTY_RECORDS + 1
            val colourRecords =
                (0 until colours).joinToString("") { "#${100000 + it}=COLOUR_RGB('',${it / 2000.0},0.,0.);\n" }
            val alphaRecords =
                (0..n / colours).joinToString("") { "#${200000 + it}=SURFACE_STYLE_TRANSPARENT(${it / 100.0});\n" }
            val renderingRecords =
                (0 until n).joinToString("") {
                    "#${300000 + it}=SURFACE_STYLE_RENDERING_WITH_PROPERTIES(.NORMAL_SHADING.," +
                        "#${100000 + it % colours},(#${200000 + it / colours}));\n"
                }
            val records = colourRecords + alphaRecords + renderingRecords
            val bytes = withStyleRecords({ records }, 1)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: colour definition count (rendering)"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "many styled items against a full colour table are rejected, quickly".config(enabled = available) {
            // Every count is under its own cap, but styled items x table size is far above the work budget.
            val colours = 5000
            val renderings = 5000
            val styled = 20_001
            val colourRecords =
                (0 until colours).joinToString("") { "#${100000 + it}=COLOUR_RGB('',${it / 5000.0},0.,0.);\n" }
            val alphaRecords =
                (0 until 100).joinToString("") { "#${200000 + it}=SURFACE_STYLE_TRANSPARENT(${it / 100.0});\n" }
            val renderingRecords =
                (0 until renderings).joinToString("") {
                    "#${300000 + it}=SURFACE_STYLE_RENDERING_WITH_PROPERTIES(.NORMAL_SHADING.," +
                        "#${100000 + it},(#${200000 + it % 100}));\n"
                }
            val styledRecords = (0 until styled).joinToString("") { "#${400000 + it}=STYLED_ITEM('',(#34),#34);\n" }
            val bytes = withStyleRecords({ colourRecords + alphaRecords + renderingRecords + styledRecords }, 1)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: styled item colour work"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "a hostile number of styled items is rejected outright, quickly".config(enabled = available) {
            val n = StepImportLimits.MAX_STYLED_ITEMS + 1
            val bytes = withStyleRecords({ "#${100000 + it}=STYLED_ITEM('',(#34),#34);\n" }, n)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: styled item count"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "a long shared style list referenced by many styled items is rejected, quickly".config(enabled = available) {
            val k = StepImportLimits.MAX_STYLE_LIST_LENGTH + 1
            val refs = (0 until k).joinToString(",") { "#100001" }
            val records =
                "#100000=COLOUR_RGB('',0.5,0.,0.);\n" +
                    "#100001=SURFACE_STYLE_RENDERING(.NORMAL_SHADING.,#100000);\n" +
                    "#100002=SURFACE_SIDE_STYLE('',($refs));\n" +
                    "#100003=SURFACE_STYLE_USAGE(.BOTH.,#100002);\n" +
                    "#100004=PRESENTATION_STYLE_ASSIGNMENT((#100003));\n"
            val styledRecords = (0 until 1000).joinToString("") { "#${200000 + it}=STYLED_ITEM('',(#100004),#34);\n" }
            val bytes = withStyleRecords({ records + styledRecords }, 1)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: style list length"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "a long presentation style assignment list is rejected, quickly".config(enabled = available) {
            val k = StepImportLimits.MAX_STYLE_LIST_LENGTH + 1
            val refs = (0 until k).joinToString(",") { "#100003" }
            val records =
                "#100000=COLOUR_RGB('',0.5,0.,0.);\n" +
                    "#100001=SURFACE_STYLE_RENDERING(.NORMAL_SHADING.,#100000);\n" +
                    "#100002=SURFACE_SIDE_STYLE('',(#100001));\n" +
                    "#100003=SURFACE_STYLE_USAGE(.BOTH.,#100002);\n" +
                    "#100004=PRESENTATION_STYLE_ASSIGNMENT(($refs));\n" +
                    "#100005=STYLED_ITEM('',(#100004),#34);\n"
            val bytes = withStyleRecords({ records }, 1)
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: style list length"
        }

        // Style chain styled item -> PSA -> SURFACE_SIDE_STYLE -> FILL_AREA_STYLE with [width] entries per list.
        fun nestedStyleRecords(
            width: Int,
            items: Int,
        ): String {
            fun refs(id: String) = List(width) { id }.joinToString(",")
            return "#100000=COLOUR_RGB('',0.5,0.,0.);\n" +
                "#100001=FILL_AREA_STYLE_COLOUR('',#100000);\n" +
                "#100002=FILL_AREA_STYLE('',(${refs("#100001")}));\n" +
                "#100003=SURFACE_STYLE_FILL_AREA(#100002);\n" +
                "#100004=SURFACE_SIDE_STYLE('',(${refs("#100003")}));\n" +
                "#100005=SURFACE_STYLE_USAGE(.BOTH.,#100004);\n" +
                "#100006=PRESENTATION_STYLE_ASSIGNMENT((${refs("#100005")}));\n" +
                (0 until items).joinToString("") { "#${200000 + it}=STYLED_ITEM('',(${refs("#100006")}),#34);\n" }
        }

        "style lists that are each within the cap but multiply past the expansion budget are rejected, quickly".config(
            enabled = available,
        ) {
            // 64^4 = 16.7M colour-reader steps per styled item; 100 items took 15 s before the weighted pre-scan.
            val bytes = withStyleRecords({ nestedStyleRecords(StepImportLimits.MAX_STYLE_LIST_LENGTH, 100) }, 1)
            val start = System.nanoTime()
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(bytes) }
            e.message.orEmpty() shouldContain "limit: style expansion work"
            ((System.nanoTime() - start) / 1_000_000_000.0 < 10.0) shouldBe true
        }

        "the expansion budget counts the whole file: one cheap chain on many styled items stays readable".config(
            enabled = available,
        ) {
            // 3^4 = 81 per styled item, 1000 items = 81k: far below the budget, like a real file.
            val bytes = withStyleRecords({ nestedStyleRecords(3, 1000) }, 1)
            OcctKernel.readStep(bytes).use { it.nodes.size shouldBe 8 }
        }

        "a FILL_AREA_STYLE with too many fill styles is rejected".config(enabled = available) {
            val refs = (0..StepImportLimits.MAX_STYLE_LIST_LENGTH).joinToString(",") { "#100001" }
            val records =
                "#100000=COLOUR_RGB('',0.5,0.,0.);\n" +
                    "#100001=FILL_AREA_STYLE_COLOUR('',#100000);\n" +
                    "#100002=FILL_AREA_STYLE('',($refs));\n" +
                    "#100003=SURFACE_STYLE_FILL_AREA(#100002);\n" +
                    "#100004=SURFACE_SIDE_STYLE('',(#100003));\n" +
                    "#100005=SURFACE_STYLE_USAGE(.BOTH.,#100004);\n" +
                    "#100006=PRESENTATION_STYLE_ASSIGNMENT((#100005));\n" +
                    "#100007=STYLED_ITEM('',(#100006),#34);\n"
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(withStyleRecords({ records }, 1)) }
            e.message.orEmpty() shouldContain "limit: style list length"
        }

        "a rendering with too many properties is rejected".config(enabled = available) {
            val refs = (0..StepImportLimits.MAX_STYLE_LIST_LENGTH).joinToString(",") { "#100001" }
            val records =
                "#100000=COLOUR_RGB('',0.5,0.,0.);\n" +
                    "#100001=SURFACE_STYLE_TRANSPARENT(0.5);\n" +
                    "#100002=SURFACE_STYLE_RENDERING_WITH_PROPERTIES(.NORMAL_SHADING.,#100000,($refs));\n" +
                    "#100004=SURFACE_SIDE_STYLE('',(#100002));\n" +
                    "#100005=SURFACE_STYLE_USAGE(.BOTH.,#100004);\n" +
                    "#100006=PRESENTATION_STYLE_ASSIGNMENT((#100005));\n" +
                    "#100007=STYLED_ITEM('',(#100006),#34);\n"
            val e = shouldThrow<OcctImportLimitException> { OcctKernel.readStep(withStyleRecords({ records }, 1)) }
            e.message.orEmpty() shouldContain "limit: style list length"
        }

        "the expansion constants match the native bridge source" {
            val cpp = File("src/main/cpp/kstep_occt_bridge.cpp").readText()

            fun constant(name: String) =
                Regex("""constexpr\s+[\w ]+?\s+$name\s*=\s*(\d+)""").find(cpp)!!.groupValues[1].toLong()
            constant("kMaxStyleExpansionWork") shouldBe StepImportLimits.MAX_STYLE_EXPANSION_WORK
            constant("kMaxExpandedOccurrences") shouldBe StepImportLimits.MAX_EXPANDED_OCCURRENCES.toLong()
            constant("kMaxStyleListLength") shouldBe StepImportLimits.MAX_STYLE_LIST_LENGTH.toLong()
            constant("kMaxAssemblyDepth") shouldBe StepImportLimits.MAX_ASSEMBLY_DEPTH.toLong()
            constant("kMaxOverrideDepth") shouldBe StepImportLimits.MAX_OVERRIDE_DEPTH.toLong()
            constant("kMaxMappedItemExpansions") shouldBe StepImportLimits.MAX_MAPPED_ITEM_EXPANSIONS
            constant("kMaxShellFaceReferences") shouldBe StepImportLimits.MAX_TOTAL_FACES.toLong()
        }

        "a moderate number of layer assignments is still read".config(enabled = available) {
            val bytes = withStyleRecords({ "#${100000 + it}=PRESENTATION_LAYER_ASSIGNMENT('L$it','',(#34));\n" }, 50)
            OcctKernel.readStep(bytes).use { it.nodes.size shouldBe 8 }
        }
    })
