package dev.kstep.tests

import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21CycleException
import dev.kstep.step21.Part21DanglingReferenceException
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21SyntaxException
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun header(): String =
    "FILE_DESCRIPTION((''),'2;1');\n" +
        "FILE_NAME('n.step','2026-09-02T00:00:00',('Author'),('kSTEP'),'','kSTEP','');\n" +
        "FILE_SCHEMA(('S'));\n"

private fun wrap(dataSection: String): String =
    "ISO-10303-21;\nHEADER;\n${header()}ENDSEC;\nDATA;\n$dataSection" +
        "ENDSEC;\nEND-ISO-10303-21;\n"

/**
 * The reader-ausbau half of ADR-0009: proves the full ISO 10303-21 value grammar (numbers,
 * enumerations, `*`, typed parameters, complex instances) parses under
 * [Part21ReadMode.TOLERANT], that known entities stay just as strict as [Part21ReadMode.STRICT]
 * in both modes, and that [Part21ReadMode.STRICT] itself is completely unchanged.
 */
class Part21TolerantReaderTest :
    StringSpec({
        "real-world numeric lexemes survive parse-then-render verbatim" {
            val lexemes = listOf("0.", "1.E-07", "-4.440892098501E-16", "-0.", "2013", "40.", "-1")
            for (lexeme in lexemes) {
                val source = wrap("#1=FOO($lexeme);\n")
                val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
                val instance = doc.instances.single() as Part21SimpleInstance
                val value = instance.args.single()
                value.shouldBeInstanceOf<Part21Value.Num>()
                value.lexeme shouldBe lexeme
            }
        }

        "an INTEGER literal is never reformatted into a REAL literal" {
            val source = wrap("#1=FOO(2013);\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            val value = (doc.instances.single() as Part21SimpleInstance).args.single()
            (value as Part21Value.Num).lexeme shouldBe "2013"
        }

        "enumerations parse without their surrounding dots" {
            val source = wrap("#1=FOO(.T.,.MILLI.,.METRE.);\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            val args = (doc.instances.single() as Part21SimpleInstance).args
            args shouldBe
                listOf(Part21Value.Enumeration("T"), Part21Value.Enumeration("MILLI"), Part21Value.Enumeration("METRE"))
        }

        "'*' parses as Derived" {
            val source = wrap("#1=FOO(*);\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            (doc.instances.single() as Part21SimpleInstance).args.single() shouldBe Part21Value.Derived
        }

        "a typed parameter parses as Typed with its keyword and nested args" {
            val source = wrap("#1=FOO(LENGTH_MEASURE(1.E-07));\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            val value = (doc.instances.single() as Part21SimpleInstance).args.single()
            value shouldBe Part21Value.Typed("LENGTH_MEASURE", listOf(Part21Value.Num("1.E-07")))
        }

        "a complex instance parses into Part21ComplexInstance with all its parts" {
            val source = wrap("#1=( A(1) B() C(*) );\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            val instance = doc.instances.single()
            instance.shouldBeInstanceOf<Part21ComplexInstance>()
            instance.parts.map { it.entityName } shouldBe listOf("A", "B", "C")
            instance.parts[0].args shouldBe listOf(Part21Value.Num("1"))
            instance.parts[2].args shouldBe listOf(Part21Value.Derived)
        }

        "an unknown entity name is opaque under TOLERANT but throws under STRICT" {
            val source = wrap("#1=WIDGET('a','b');\n")

            val tolerant = Part21Reader.read(source, Part21ReadMode.TOLERANT)
            tolerant.opaque.keys shouldBe setOf(1)
            tolerant.instances.keys shouldBe emptySet()

            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.STRICT) }
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source) } // default is STRICT
        }

        "a known entity is still typed-constructed under TOLERANT, not left opaque" {
            val source = wrap("#1=APPLICATION_CONTEXT('config control');\n")
            val result = Part21Reader.read(source, Part21ReadMode.TOLERANT)
            result.opaque.keys shouldBe emptySet()
            result.instances.keys shouldBe setOf(1)
        }

        "a known entity with the wrong arity still throws under TOLERANT" {
            val source = wrap("#1=APPLICATION_CONTEXT('a','b');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.TOLERANT) }
        }

        "a dangling reference inside a typed parameter is detected" {
            val source = wrap("#1=FOO(LENGTH_MEASURE(#2));\n")
            shouldThrow<Part21DanglingReferenceException> {
                Part21Reader.read(source, Part21ReadMode.TOLERANT)
            }
        }

        "a reference cycle through a complex-instance part is detected" {
            val source =
                wrap(
                    "#1=( A(#2) );\n" +
                        "#2=( B(#1) );\n",
                )
            shouldThrow<Part21CycleException> {
                Part21Reader.read(source, Part21ReadMode.TOLERANT)
            }
        }

        "a '#' inside a string literal is never mistaken for a reference" {
            val source = wrap("#1=FOO('Context #1');\n")
            val doc = Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
            val instance = doc.instances.single() as Part21SimpleInstance
            instance.args.single() shouldBe Part21Value.Str("Context #1")
            instance.referencedIds() shouldBe emptyList()
        }

        // Regression for the crash a known entity's REFERENCE argument used to trigger when it
        // targets a complex instance that happens to carry the expected entity name among its
        // parts (#1 here is both a PRODUCT and a SOME_OTHER simultaneously): entityNamesOf's
        // union-of-part-names made checkOneReferenceTarget accept the reference, but `construct`
        // never typed-constructs a Part21ComplexInstance, so `built.getValue(1)` used to throw an
        // undocumented NoSuchElementException instead of a structured Part21SyntaxException.
        "a known entity referencing a complex instance matching the expected type throws Part21SyntaxException" {
            val source =
                wrap(
                    "#3=APPLICATION_CONTEXT('cc');\n" +
                        "#2=PRODUCT_CONTEXT('n',#3,'d');\n" +
                        "#1=( PRODUCT('a','b',\$,(#2)) SOME_OTHER() );\n" +
                        "#4=PRODUCT_DEFINITION_FORMATION('f',\$,#1);\n",
                )
            val exception = shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.TOLERANT) }
            exception.message shouldContain "PRODUCT_DEFINITION_FORMATION #4"
            exception.message shouldContain "#1"
            exception.message shouldContain "complex instance"
        }

        // Regression for the reader/writer asymmetry (see Part21ReaderTest's STRICT-mode
        // counterpart): readRawDocument(TOLERANT) is the exact entry point
        // Ap242ShapeExporter.export feeds OCCT's own STEP output through, so if a foreign file
        // writes a non-ASCII name as a \X2\.../\X0\ escape sequence, that must fail here — at the
        // read boundary, with a message pointing at the offending input — rather than downstream
        // in Part21Document.render() with a message that looks like a renderer bug.
        "readRawDocument under TOLERANT also rejects a reverse solidus, matching STRICT and the writer" {
            val source = wrap("#1=FOO('SAFE\\X2\\04100420\\X0\\');\n")
            shouldThrow<Part21EncodingException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
        }

        // Regression for the reader/writer identifier asymmetry: parseIdentifier used to accept a
        // leading digit ("9FOO" parsed to entityName "9FOO"), unlike Part21Renderer's IDENTIFIER
        // regex ([A-Za-z_][A-Za-z0-9_]*), which requires a leading letter/underscore. Under
        // TOLERANT, an unknown entity name is normally left opaque rather than rejected (see "an
        // unknown entity name is opaque under TOLERANT" above) -- so this used to be the one case
        // where the reader accepted a document that Part21Renderer would then refuse to re-render,
        // failing much later than the read boundary with a message that looked like a renderer
        // bug rather than a malformed-input problem.
        "an opaque entity name starting with a digit is rejected at the read boundary under TOLERANT, not left opaque" {
            val source = wrap("#1=9FOO('a');\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.TOLERANT) }
        }

        // Regression for the reader/writer identifier asymmetry surviving for non-ASCII
        // characters: the leading-digit fix above only special-cased digits, but
        // Char.isLetter()/isLetterOrDigit() are Unicode-aware while Part21Renderer's IDENTIFIER
        // regex ([A-Za-z_][A-Za-z0-9_]*) is ASCII-only, so a Unicode letter/digit in an entity
        // name, enumeration literal, or typed-parameter keyword still parsed here but was
        // rejected at render time — reproducing the exact same read/write asymmetry for a
        // different character class. All four cases below used to parse successfully under
        // TOLERANT (and STRICT, for the entity-name cases) and only fail later in
        // Part21Document.render(); now they fail at the read boundary.
        "a non-ASCII letter in an entity name is rejected at the read boundary, not left opaque" {
            val source = wrap("#1=FUÜBAR('a');\n") // U+00DC = 'Ü'
            shouldThrow<Part21SyntaxException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.TOLERANT) }
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.STRICT) }
        }

        "a non-ASCII decimal digit in an entity name is rejected at the read boundary" {
            val source = wrap("#1=FOO٥('a');\n") // U+0665 = Arabic-Indic digit five
            shouldThrow<Part21SyntaxException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
            shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.STRICT) }
        }

        "a non-ASCII letter in an enumeration literal is rejected at the read boundary" {
            val source = wrap("#1=FOO(.MILLÉ.);\n") // U+00C9 = 'É'
            shouldThrow<Part21SyntaxException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
        }

        "a non-ASCII letter in a typed-parameter keyword is rejected at the read boundary" {
            val source = wrap("#1=FOO(KÜW(1));\n") // U+00DC = 'Ü'
            shouldThrow<Part21SyntaxException> { Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT) }
        }

        // Regression for the unbounded-echo/log-amplification gap: before this fix, neither
        // parseIdentifier nor the two messages that echo a raw parsed identifier (HEADER
        // statement-name mismatch, unknown entity name) had any length bound beyond
        // MAX_SOURCE_LENGTH (5,000,000 characters), so a pathological file could make
        // Part21Tokenizer throw a multi-megabyte exception message. parseIdentifier now caps any
        // single identifier at 128 characters.
        "an identifier exceeding the maximum supported length is rejected rather than echoed unbounded" {
            val hugeName = "A".repeat(500)
            val source = wrap("#1=$hugeName('a');\n")
            val exception =
                shouldThrow<Part21LimitExceededException> {
                    Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
                }
            exception.message shouldContain "128"
        }

        "a HEADER argument with the wrong shape and a huge payload is described, not echoed unbounded" {
            // Regression for the round-3 finding: buildHeader's asString/asStringList used to
            // interpolate the raw Part21Value into the exception message, whose data-class
            // toString() echoes the entire parsed payload (bounded only by MAX_SOURCE_LENGTH,
            // 5,000,000 chars) instead of the fixed-word describeShape(value) used everywhere
            // else. FILE_DESCRIPTION.description must be a LIST; giving it a plain STRING with a
            // huge payload previously produced a multi-hundred-KB exception message.
            val hugePayload = "C".repeat(500_000)
            val source =
                "ISO-10303-21;\nHEADER;\n" +
                    "FILE_DESCRIPTION('$hugePayload','2;1');\n" +
                    "FILE_NAME('n.step','2026-09-02T00:00:00',('Author'),('kSTEP'),'','kSTEP','');\n" +
                    "FILE_SCHEMA(('S'));\n" +
                    "ENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;\n"
            val exception =
                shouldThrow<Part21SyntaxException> {
                    Part21Reader.readRawDocument(source, Part21ReadMode.STRICT)
                }
            exception.message shouldContain "STRING"
            (exception.message?.length ?: 0) shouldBeLessThan 200
        }

        "a HEADER argument with a NUMBER where a STRING is required is described, not echoed unbounded" {
            // Same finding, the asString (rather than asStringList) call site: FILE_NAME.name
            // must be a STRING; giving it a NUMBER lexeme with a huge digit run previously
            // produced a multi-hundred-KB exception message via Part21Value.Num's toString().
            val hugeDigits = "9".repeat(500_000)
            val source =
                "ISO-10303-21;\nHEADER;\n" +
                    "FILE_DESCRIPTION((''),'2;1');\n" +
                    "FILE_NAME($hugeDigits,'2026-09-02T00:00:00',('Author'),('kSTEP'),'','kSTEP','');\n" +
                    "FILE_SCHEMA(('S'));\n" +
                    "ENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;\n"
            val exception =
                shouldThrow<Part21SyntaxException> {
                    Part21Reader.readRawDocument(source, Part21ReadMode.STRICT)
                }
            exception.message shouldContain "a NUMBER"
            (exception.message?.length ?: 0) shouldBeLessThan 200
        }

        // Regression for the round-4 finding: parseInstanceId's "not a valid integer" message used
        // to interpolate the raw, unbounded digit run it had just accumulated (no
        // MAX_IDENTIFIER_LENGTH-style cap applies to it, since it is numeric, not an identifier),
        // so a `#<huge digit run>=...` instance id produced a multi-hundred-KB exception message.
        // It now goes through the same truncateForMessage helper the HEADER-shape messages above
        // use.
        "an instance id with a huge digit run is truncated in its exception message, not echoed unbounded" {
            val hugeDigits = "9".repeat(500_000)
            val source = wrap("#$hugeDigits=FOO('a');\n")
            val exception =
                shouldThrow<Part21SyntaxException> {
                    Part21Reader.readRawDocument(source, Part21ReadMode.TOLERANT)
                }
            exception.message shouldContain "not a valid integer"
            (exception.message?.length ?: 0) shouldBeLessThan 200
        }

        // Regression for the round-4 finding: checkDangling used to joinToString every dangling
        // (from, to) pair into one message with no cap, so a file with many opaque instances that
        // each reference one undefined `#N` produced a message that grew linearly with the whole
        // file (observed: a 260 KB input produced a 520 KB message) rather than with
        // MAX_SOURCE_LENGTH. It now reports at most MAX_DANGLING_REFERENCES_REPORTED (20) pairs
        // and summarizes the rest.
        "many dangling references are capped in the exception message, not echoed unbounded" {
            val dataSection = (1..500).joinToString("") { i -> "#$i=FOO(#${i + 100_000});\n" }
            val source = wrap(dataSection)
            val exception =
                shouldThrow<Part21DanglingReferenceException> {
                    Part21Reader.read(source, Part21ReadMode.TOLERANT)
                }
            exception.message shouldContain "and 480 more dangling reference(s) not shown"
            (exception.message?.length ?: 0) shouldBeLessThan 2_000
        }

        "Part21Reader.read without a mode argument behaves exactly like STRICT" {
            val source = wrap("#1=WIDGET('a','b');\n")
            val explicitStrict = shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.STRICT) }
            val implicitDefault = shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }
            implicitDefault.message shouldBe explicitStrict.message
        }
    })
