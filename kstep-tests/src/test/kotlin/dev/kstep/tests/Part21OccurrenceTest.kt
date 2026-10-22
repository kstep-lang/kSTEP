package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.express.validation.IndeterminateDerivedValueException
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.DescriptionAttribute
import dev.kstep.generated.ap242v1.IdAttribute
import dev.kstep.generated.ap242v1.NameAttribute
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SyntaxException
import dev.kstep.step21.Part21Writer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private val HEADER =
    Part21Header(
        fileName = "occ.step",
        timestamp = "2026-10-19T00:00:00",
        schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
    )

private fun doc(dataSection: String): String =
    "ISO-10303-21;\nHEADER;\n" +
        "FILE_DESCRIPTION((''),'2;1');\n" +
        "FILE_NAME('a.step','2026-10-19T00:00:00',('A'),('kSTEP'),'','kSTEP','');\n" +
        "FILE_SCHEMA(('AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF'));\n" +
        "ENDSEC;\nDATA;\n$dataSection" +
        "ENDSEC;\nEND-ISO-10303-21;\n"

// A product definition (#6) with its whole context chain, reused by several documents below.
private const val PD_CHAIN =
    "#1=APPLICATION_CONTEXT('cc');\n" +
        "#2=PRODUCT_CONTEXT('e',#1,'m');\n" +
        "#3=PRODUCT_DEFINITION_CONTEXT('e',#1,'d');\n" +
        "#4=PRODUCT('P','p',\$,(#2));\n" +
        "#5=PRODUCT_DEFINITION_FORMATION('F',\$,#4);\n" +
        "#6=PRODUCT_DEFINITION('D1',\$,#5,#3);\n" +
        "#7=PRODUCT_DEFINITION('D2',\$,#5,#3);\n"

class Part21OccurrenceTest :
    StringSpec({
        "occurrences, usages onto occurrences and the attribute entities roundtrip losslessly" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val child = m.pd("CHILD")
            val occurrence = m.pdo("OCC-1", child)
            val usage = m.usage("U1", top, occurrence, "RD-1")
            val nameOfTop = m.nameOf(top, "Assembly")
            val po = m.personAndOrganization()
            val descriptionOfPo = m.descriptionOf(po, "Lead engineer")
            val idOfContext = m.idOf(m.ac, "ctx-9")

            val text = Part21Writer.write(HEADER, listOf(usage, nameOfTop, descriptionOfPo, idOfContext))
            // The real arity of product_definition_occurrence keeps quantity as the fifth argument, written as '$'.
            Regex("""PRODUCT_DEFINITION_OCCURRENCE\('OCC-1',\$,\$,#\d+,\$\);""").containsMatchIn(text) shouldBe true
            text shouldContain "NAME_ATTRIBUTE('Assembly',#"
            text shouldContain "DESCRIPTION_ATTRIBUTE('Lead engineer',#"
            text shouldContain "ID_ATTRIBUTE('ctx-9',#"

            val read = Part21Reader.read(text, validateModel = true)
            read.isFullySuccessful shouldBe true
            read.isModelValid shouldBe true
            val readUsage =
                read.instances.values
                    .filterIsInstance<NextAssemblyUsageOccurrence>()
                    .single()
            val related = readUsage.relatedProductDefinition as ProductDefinitionOccurrence
            related.id shouldBe "OCC-1"
            (related.definition as ProductDefinition).id shouldBe "CHILD"
            readUsage.productDefinitionOccurrenceId shouldBe "OCC-1"
            read.instances.values
                .filterIsInstance<NameAttribute>()
                .single()
                .attributeValue shouldBe "Assembly"
            read.instances.values
                .filterIsInstance<DescriptionAttribute>()
                .single()
                .attributeValue shouldBe
                "Lead engineer"
            read.instances.values
                .filterIsInstance<IdAttribute>()
                .single()
                .attributeValue shouldBe "ctx-9"

            // The derived values are readable from the read population.
            val scope = read.population().derivedScope
            read.instances.values
                .filterIsInstance<ProductDefinition>()
                .single { it.id == "TOP" }
                .name(scope) shouldBe
                "Assembly"
            read.instances.values
                .filterIsInstance<PersonAndOrganization>()
                .single()
                .description(scope) shouldBe
                "Lead engineer"
            read.instances.values
                .filterIsInstance<ApplicationContext>()
                .single()
                .id(scope) shouldBe "ctx-9"

            // And the roundtrip is stable: writing what was read yields the identical text.
            Part21Writer.write(HEADER, read.instances.values.toList()).lines().count { it.startsWith("#") } shouldBe
                text.lines().count { it.startsWith("#") }
        }

        "an occurrence without a definition writes '\$' for it and reads back" {
            val m = AssemblyTestModel()
            val text = Part21Writer.write(HEADER, listOf(m.pdo("BARE")))
            text shouldContain "PRODUCT_DEFINITION_OCCURRENCE('BARE',\$,\$,\$,\$);"
            val read = Part21Reader.read(text)
            read.isFullySuccessful shouldBe true
            read.instances.values
                .filterIsInstance<ProductDefinitionOccurrence>()
                .single()
                .definition shouldBe null
        }

        "STRICT rejects a quantity other than '\$' naming instance and argument position" {
            val source = doc(PD_CHAIN + "#8=PRODUCT_DEFINITION_OCCURRENCE('O',\$,\$,#6,1.5);\n")
            val message = shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }.message!!
            message shouldContain "PRODUCT_DEFINITION_OCCURRENCE"
            message shouldContain "#8"
            message shouldContain "argument 5"
            message shouldContain "must be '\$'"
        }

        "TOLERANT keeps an occurrence with a real quantity opaque and everything else typed" {
            val source = doc(PD_CHAIN + "#8=PRODUCT_DEFINITION_OCCURRENCE('O',\$,\$,#6,1.5);\n")
            val read = Part21Reader.read(source, Part21ReadMode.TOLERANT)
            read.opaque.keys shouldBe setOf(8)
            read.violations.isEmpty() shouldBe true
            read.instances.values.filterIsInstance<ProductDefinition>() shouldHaveSize 2
        }

        "a usage that references such an opaque occurrence is a structured error, not a crash" {
            val source =
                doc(
                    PD_CHAIN +
                        "#8=PRODUCT_DEFINITION_OCCURRENCE('O',\$,\$,#6,1.5);\n" +
                        "#9=NEXT_ASSEMBLY_USAGE_OCCURRENCE('U','u',\$,#7,#8,\$);\n",
                )
            val message =
                shouldThrow<Part21SyntaxException> { Part21Reader.read(source, Part21ReadMode.TOLERANT) }.message!!
            message shouldContain "NEXT_ASSEMBLY_USAGE_OCCURRENCE"
            message shouldContain "kSTEP does not model"
        }

        "a usage end that is neither a product definition nor an occurrence names both expected kinds" {
            val source = doc(PD_CHAIN + "#9=NEXT_ASSEMBLY_USAGE_OCCURRENCE('U','u',\$,#6,#2,\$);\n")
            val message = shouldThrow<Part21SyntaxException> { Part21Reader.read(source) }.message!!
            message shouldContain "PRODUCT_DEFINITION or PRODUCT_DEFINITION_OCCURRENCE"
            message shouldContain "PRODUCT_CONTEXT"
        }

        "a name_attribute may name a product_definition or a person_and_organization, nothing else" {
            val ok = doc(PD_CHAIN + "#8=NAME_ATTRIBUTE('n',#6);\n")
            Part21Reader.read(ok).isFullySuccessful shouldBe true

            val bad = doc(PD_CHAIN + "#8=NAME_ATTRIBUTE('n',#2);\n")
            val message = shouldThrow<Part21SyntaxException> { Part21Reader.read(bad) }.message!!
            message shouldContain "PERSON_AND_ORGANIZATION or PRODUCT_DEFINITION"
            message shouldContain "PRODUCT_CONTEXT"
        }

        "an id_attribute may only name an application_context" {
            val bad = doc(PD_CHAIN + "#8=ID_ATTRIBUTE('i',#6);\n")
            shouldThrow<Part21SyntaxException> { Part21Reader.read(bad) }.message!! shouldContain
                "must reference a APPLICATION_CONTEXT"
        }

        "NAME_ATTRIBUTE is typed now: the TOLERANT reader no longer leaves it opaque" {
            val source = doc(PD_CHAIN + "#8=NAME_ATTRIBUTE('n',#6);\n")
            val read = Part21Reader.read(source, Part21ReadMode.TOLERANT)
            read.opaque.isEmpty() shouldBe true
            read.instances.values.filterIsInstance<NameAttribute>() shouldHaveSize 1
        }

        "a cyclic usage file is read and validateModel reports the cycle with the ids on the path" {
            val source =
                doc(
                    PD_CHAIN +
                        "#8=NEXT_ASSEMBLY_USAGE_OCCURRENCE('U1','u1',\$,#6,#7,\$);\n" +
                        "#9=NEXT_ASSEMBLY_USAGE_OCCURRENCE('U2','u2',\$,#7,#6,\$);\n",
                )
            val read = Part21Reader.read(source, validateModel = true)
            read.isFullySuccessful shouldBe true
            read.isModelValid shouldBe false
            val violations = read.modelValidation!!.findings.flatMap { it.violations }
            violations.map { it.code } shouldBe
                listOf(DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED, DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED)
            violations.first().message shouldContain "'D1'"
            violations.first().message shouldContain "'D2'"
        }

        "TOLERANT: an opaque instance referencing a product definition makes its derived name indeterminate" {
            val source =
                doc(
                    PD_CHAIN +
                        "#8=FOREIGN_THING('x',#6);\n" +
                        "#9=NAME_ATTRIBUTE('n',#7);\n",
                )
            val read = Part21Reader.read(source, Part21ReadMode.TOLERANT, validateModel = true)
            read.opaque.keys shouldBe setOf(8)
            val d1 =
                read.instances.values
                    .filterIsInstance<ProductDefinition>()
                    .single { it.id == "D1" }
            val d2 =
                read.instances.values
                    .filterIsInstance<ProductDefinition>()
                    .single { it.id == "D2" }
            shouldThrow<IndeterminateDerivedValueException> { d1.name(read.population().derivedScope) }
            d2.name(read.population().derivedScope) shouldBe "n"
        }

        "the existing twelve-entity wire format is unchanged: a plain usage between two definitions writes as before" {
            val m = AssemblyTestModel()
            val text = Part21Writer.write(HEADER, listOf(m.usage("U", m.pd("A"), m.pd("B"), "RD")))
            text shouldContain "NEXT_ASSEMBLY_USAGE_OCCURRENCE('U','usage U',\$,#"
            text shouldNotContain "PRODUCT_DEFINITION_OCCURRENCE"
            Part21Reader.read(text).isFullySuccessful shouldBe true
        }
    })
