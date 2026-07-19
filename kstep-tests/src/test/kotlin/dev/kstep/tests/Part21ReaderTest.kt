package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.step21.Part21CycleException
import dev.kstep.step21.Part21DanglingReferenceException
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

private fun header(schema: String = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"): String =
    "FILE_DESCRIPTION((''),'2;1');\n" +
        "FILE_NAME('bracket.step','2026-07-19T12:00:00',('Author'),('kSTEP'),'','kSTEP','');\n" +
        "FILE_SCHEMA(('$schema'));\n"

private fun wrap(dataSection: String): String =
    "ISO-10303-21;\nHEADER;\n${header()}ENDSEC;\nDATA;\n$dataSection" +
        "ENDSEC;\nEND-ISO-10303-21;\n"

class Part21ReaderTest :
    StringSpec({
        "a missing trailing semicolon on a DATA statement throws Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket')\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a malformed instance id missing digits after '#' throws Part21SyntaxException" {
            val source = wrap("#=PRODUCT('BRK-001','Bracket','Mounting bracket');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a malformed instance statement missing the leading '#' throws Part21SyntaxException" {
            val source = wrap("1=PRODUCT('BRK-001','Bracket','Mounting bracket');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "mismatched/unbalanced parentheses throw Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket';\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "an unknown entity name throws Part21SyntaxException" {
            val source = wrap("#1=WIDGET('a','b','c');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "wrong arity (PRODUCT given two args instead of three) throws Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a reference where PRODUCT expects an all-STRING argument throws Part21SyntaxException" {
            val source = wrap("#1=PERSON_AND_ORGANIZATION('Jane Doe','Acme');\n#2=PRODUCT(#1,'Bracket','x');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a reference pointing at the wrong target entity type throws Part21SyntaxException" {
            val source =
                wrap(
                    "#1=PRODUCT('BRK-001','Bracket','x');\n" +
                        "#2=APPROVAL('APPROVED','A1',#1);\n",
                )
            val exception = shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
            exception.message shouldBe
                "APPROVAL #2 argument 3 must reference a PERSON_AND_ORGANIZATION, but #1 is a PRODUCT"
        }

        "a dangling reference (never-defined #N) throws Part21DanglingReferenceException naming the offending id" {
            val source = wrap("#1=PRODUCT_DEFINITION_FORMATION('PDF-001','',#2);\n")
            val exception = shouldThrow<Part21DanglingReferenceException> { Part21Reader.read(source) }
            exception.message shouldBe "#1 references #2, which is never defined in DATA"
        }

        // #1=APPROVAL(...,#2) and #2=PRODUCT_DEFINITION_FORMATION(...,#1) are individually valid at
        // pass 1 (both position-3/-3 args are REFERENCE-kind for their respective entities), so this
        // cycle is only detected in the resolver's type-agnostic pass-2a, before pass-2b's per-reference
        // target-type check would otherwise flag the (also-wrong) target types first.
        "a reference cycle throws Part21CycleException" {
            val source =
                wrap(
                    "#1=APPROVAL('s','l',#2);\n" +
                        "#2=PRODUCT_DEFINITION_FORMATION('id','',#1);\n",
                )
            shouldThrow<Part21CycleException> { Part21Reader.read(source) }
        }

        // Matches the internal MAX_VALUE_NESTING_DEPTH = 32 guard in Part21Tokenizer — kept in sync
        // manually per this module's DoS-guard cross-module-visibility constraint.
        "a pathologically deep nested list in a HEADER field throws Part21LimitExceededException" {
            val deeplyNested = "(".repeat(40) + "'x'" + ")".repeat(40)
            val source =
                "ISO-10303-21;\nHEADER;\n" +
                    "FILE_DESCRIPTION($deeplyNested,'2;1');\n" +
                    "FILE_NAME('n','t',('a'),('o'),'','','');\n" +
                    "FILE_SCHEMA(('S'));\n" +
                    "ENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;\n"
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(source) }
        }

        // Matches the internal MAX_REFERENCE_CHAIN_DEPTH = 64 guard in Part21GraphResolver. Uses
        // forward references (#i -> #(i+1), not #i -> #(i-1)) so a single DFS call starting at #1
        // actually has to descend the whole chain before any node is already-DONE and short-circuits
        // it — a backward-referencing chain would never grow past depth 2, since document order
        // already resolves each predecessor as its own topological-sort start point first.
        "a reference chain longer than the supported depth throws Part21LimitExceededException" {
            val chainLength = 80
            val sb = StringBuilder()
            for (i in 1 until chainLength) {
                if (i % 2 == 1) {
                    sb.append("#$i=APPROVAL('s','l',#${i + 1});\n")
                } else {
                    sb.append("#$i=PRODUCT_DEFINITION_FORMATION('id$i','',#${i + 1});\n")
                }
            }
            sb.append("#$chainLength=PRODUCT('leaf','','');\n")
            val source = wrap(sb.toString())
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(source) }
        }

        // Matches the internal MAX_SOURCE_LENGTH = 5_000_000 guard in Part21Tokenizer.
        "an oversized source string throws Part21LimitExceededException" {
            val oversized = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket');\n") + " ".repeat(5_000_001)
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(oversized) }
        }

        // Matches the internal MAX_INSTANCES = 10_000 guard in Part21Tokenizer.
        "more than the supported instance count throws Part21LimitExceededException" {
            val sb = StringBuilder()
            for (i in 1..10_001) {
                sb.append("#$i=PRODUCT('P$i','',''); \n")
            }
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(wrap(sb.toString())) }
        }

        "a hand-edited file with an empty PRODUCT id surfaces as a WHERE-rule violation, dependent skipped" {
            val source =
                wrap(
                    "#1=PRODUCT('','',''); \n" +
                        "#2=PRODUCT_DEFINITION_FORMATION('PDF-001','',#1);\n",
                )
            val result = Part21Reader.read(source)

            result.isFullySuccessful shouldBe false
            result.violations shouldContainKey 1
            val violation = result.violations.getValue(1).single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            result.skipped shouldBe mapOf(2 to listOf(1))
            result.instances.containsKey(1) shouldBe false
            result.instances.containsKey(2) shouldBe false
        }

        // KSTEP-M-001 (missing mandatory reference) is structurally unreachable from the reader: every
        // positional argument (including reference positions) is mandatory at the pass-1 arity check,
        // so the reconstruction path always sets every builder property before calling it. Only
        // KSTEP-W-001 (WHERE rule) violations can ever appear here.
        "only KSTEP-W-001 violations are ever produced by the reader, never KSTEP-M-001" {
            val source =
                wrap(
                    "#1=PRODUCT('','',''); \n" +
                        "#2=PRODUCT_DEFINITION_FORMATION('PDF-001','',#1);\n",
                )
            val result = Part21Reader.read(source)
            result.violations.values
                .flatten()
                .map { it.code }
                .toSet() shouldBe
                setOf(DslViolationCodes.WHERE_RULE_NOT_SATISFIED)
        }

        "the header round-trips all seven Part21Header fields" {
            val source =
                "ISO-10303-21;\nHEADER;\n" +
                    "FILE_DESCRIPTION(('a description'),'2;1');\n" +
                    "FILE_NAME('n.step','2026-07-19T12:00:00',('Author'),('Org'),'pre 1.0','kSTEP','auth');\n" +
                    "FILE_SCHEMA(('SCHEMA_A','SCHEMA_B'));\n" +
                    "ENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;\n"
            val result = Part21Reader.read(source)

            result.header.fileName shouldBe "n.step"
            result.header.timestamp shouldBe "2026-07-19T12:00:00"
            result.header.schemaIdentifiers shouldBe listOf("SCHEMA_A", "SCHEMA_B")
            result.header.description shouldBe listOf("a description")
            result.header.implementationLevel shouldBe "2;1"
            result.header.author shouldBe listOf("Author")
            result.header.organization shouldBe listOf("Org")
            result.header.preprocessorVersion shouldBe "pre 1.0"
            result.header.originatingSystem shouldBe "kSTEP"
            result.header.authorization shouldBe "auth"
        }
    })
