package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.step21.Part21CycleException
import dev.kstep.step21.Part21DanglingReferenceException
import dev.kstep.step21.Part21EncodingException
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
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket',())\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a malformed instance id missing digits after '#' throws Part21SyntaxException" {
            val source = wrap("#=PRODUCT('BRK-001','Bracket','Mounting bracket',());\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a malformed instance statement missing the leading '#' throws Part21SyntaxException" {
            val source = wrap("1=PRODUCT('BRK-001','Bracket','Mounting bracket',());\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "mismatched/unbalanced parentheses throw Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket',();\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "an unknown entity name throws Part21SyntaxException" {
            val source = wrap("#1=WIDGET('a','b','c');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        // STRICT mode already rejected "9FOO" as an unknown entity name before the parseIdentifier
        // fix below; this test just pins that STRICT behavior. The actual before/after regression
        // -- a leading digit being accepted by the reader and only rejected much later, at render
        // time, by Part21Renderer -- is only observable under TOLERANT, where an unknown entity
        // name is otherwise left opaque rather than rejected; see Part21TolerantReaderTest.
        "an entity name starting with a digit throws Part21SyntaxException (renderer identifier grammar)" {
            val source = wrap("#1=9FOO('a');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "wrong arity (PRODUCT given three args instead of four) throws Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','x');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a reference where PRODUCT expects an all-STRING first argument throws Part21SyntaxException" {
            val source = wrap("#1=APPLICATION_CONTEXT('x');\n#2=PRODUCT(#1,'Bracket','x',());\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a bare string where a REFERENCE_LIST is expected throws Part21SyntaxException" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','x','not-a-list');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a list mixing a string among references in a REFERENCE_LIST throws Part21SyntaxException" {
            val source =
                wrap(
                    "#1=APPLICATION_CONTEXT('x');\n" +
                        "#2=PRODUCT_CONTEXT('eng',#1,'mech');\n" +
                        "#3=PRODUCT('BRK-001','Bracket','x',(#2,'oops'));\n",
                )
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "'\$' at a non-OPTIONAL position throws Part21SyntaxException" {
            // PRODUCT's 'name' (position 2) is not OPTIONAL in the real schema.
            val source = wrap("#1=PRODUCT('BRK-001',\$,'x',());\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
        }

        "a reference pointing at the wrong target entity type throws Part21SyntaxException" {
            val source =
                wrap(
                    "#1=PRODUCT('BRK-001','Bracket','x',());\n" +
                        "#2=APPROVAL(#1,'A1');\n",
                )
            val exception = shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
            exception.message shouldBe
                "APPROVAL #2 argument 1 must reference a APPROVAL_STATUS, but #1 is a PRODUCT"
        }

        "a dangling reference (never-defined #N) throws Part21DanglingReferenceException naming the offending id" {
            val source = wrap("#1=PRODUCT_DEFINITION_FORMATION('PDF-001','',#2);\n")
            val exception = shouldThrow<Part21DanglingReferenceException> { Part21Reader.read(source) }
            exception.message shouldBe "#1 references #2, which is never defined in DATA"
        }

        // #1 and #2 are both PRODUCT_DEFINITION_FORMATION, each pointing its (REFERENCE-shaped, per
        // pass 1) ofProduct position at the other — individually shape-valid at pass 1, so this cycle
        // is only detected in the resolver's type-agnostic pass-2a, before pass-2b's per-reference
        // target-type check (which would otherwise flag both as "not a PRODUCT" first).
        "a reference cycle throws Part21CycleException" {
            val source =
                wrap(
                    "#1=PRODUCT_DEFINITION_FORMATION('a','',#2);\n" +
                        "#2=PRODUCT_DEFINITION_FORMATION('b','',#1);\n",
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
                sb.append("#$i=PRODUCT_DEFINITION_FORMATION('id$i','',#${i + 1});\n")
            }
            sb.append("#$chainLength=PRODUCT('leaf','leaf','',());\n")
            val source = wrap(sb.toString())
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(source) }
        }

        // Matches the internal MAX_SOURCE_LENGTH = 5_000_000 guard in Part21Tokenizer.
        "an oversized source string throws Part21LimitExceededException" {
            val oversized = wrap("#1=PRODUCT('BRK-001','Bracket','Mounting bracket',());\n") + " ".repeat(5_000_001)
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(oversized) }
        }

        // Matches the internal MAX_INSTANCES = 10_000 guard in Part21Tokenizer.
        "more than the supported instance count throws Part21LimitExceededException" {
            val sb = StringBuilder()
            for (i in 1..10_001) {
                sb.append("#$i=PRODUCT('P$i','P$i','',()); \n")
            }
            shouldThrow<Part21LimitExceededException> { Part21Reader.read(wrap(sb.toString())) }
        }

        "a hand-edited file with an empty PRODUCT id surfaces as a WHERE-rule violation, dependent skipped" {
            val source =
                wrap(
                    "#1=APPLICATION_CONTEXT('cc');\n" +
                        "#2=PRODUCT_CONTEXT('eng',#1,'mech');\n" +
                        "#3=PRODUCT('','x','',(#2)); \n" +
                        "#4=PRODUCT_DEFINITION_FORMATION('PDF-001','',#3);\n",
                )
            val result = Part21Reader.read(source)

            result.isFullySuccessful shouldBe false
            result.violations shouldContainKey 3
            val violation = result.violations.getValue(3).single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            result.skipped shouldBe mapOf(4 to listOf(3))
            result.instances.containsKey(3) shouldBe false
            result.instances.containsKey(4) shouldBe false
        }

        "a hand-edited file with an empty PRODUCT frame_of_reference list surfaces as an aggregation-bound violation" {
            val source = wrap("#1=PRODUCT('BRK-001','Bracket','',()); \n")
            val result = Part21Reader.read(source)

            result.isFullySuccessful shouldBe false
            val violation = result.violations.getValue(1).single()
            violation.code shouldBe DslViolationCodes.AGGREGATION_BOUND_VIOLATED
        }

        "a hand-edited file with '\$' at every OPTIONAL PERSON position builds successfully" {
            val source = wrap("#1=PERSON('P-001',\$,'Jane',\$,\$,\$);\n")
            val result = Part21Reader.read(source)
            result.isFullySuccessful shouldBe true
        }

        "a hand-edited file with an empty PERSON middle_names list surfaces as an aggregation-bound violation" {
            val source = wrap("#1=PERSON('P-001',\$,'Jane',(),\$,\$);\n")
            val result = Part21Reader.read(source)

            result.isFullySuccessful shouldBe false
            val violation = result.violations.getValue(1).single()
            violation.code shouldBe DslViolationCodes.AGGREGATION_BOUND_VIOLATED
        }

        // Regression for the reader/writer asymmetry where the tokenizer silently accepted an
        // unescaped reverse solidus as literal text (0x5C is within the printable-ASCII range it
        // otherwise allows) while Part21Renderer/Part21Writer reject it — a document the reader
        // just parsed could never be rendered back. The reader now rejects it symmetrically with
        // the writer, matching Part21Renderer.assertEncodable exactly.
        "a string literal containing a reverse solidus throws Part21EncodingException, matching the writer" {
            val source = wrap("#1=PRODUCT('BRK\\001','Bracket','x',());\n")
            shouldThrow<Part21EncodingException> { Part21Reader.read(source) }
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
