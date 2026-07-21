package dev.kstep.tests

import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21WriteException
import dev.kstep.step21.Part21Writer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun testHeader(): Part21Header =
    Part21Header(
        fileName = "bracket.step",
        timestamp = "2026-07-19T12:00:00",
        schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
        description = listOf("kSTEP Part-21 writer test fixture"),
        author = listOf("Author"),
        organization = listOf("kSTEP"),
    )

class Part21WriterTest :
    StringSpec({
        "header statements serialize all fields in FILE_DESCRIPTION/FILE_NAME/FILE_SCHEMA order" {
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(product))

            text shouldContain "ISO-10303-21;\n"
            text shouldContain "HEADER;\n"
            text shouldContain
                "FILE_DESCRIPTION(('kSTEP Part-21 writer test fixture'),'2;1');\n"
            text shouldContain
                "FILE_NAME('bracket.step','2026-07-19T12:00:00',('Author'),('kSTEP'),'','kSTEP','');\n"
            text shouldContain
                "FILE_SCHEMA(('AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF'));\n"
            text shouldContain "ENDSEC;\nDATA;\n"
            text shouldContain "ENDSEC;\nEND-ISO-10303-21;\n"
        }

        "FILE_DESCRIPTION renders an empty description list as an empty parenthesis" {
            val product = product("BRK-001") { name = "" }.getOrThrow()
            val text = Part21Writer.write(testHeader().copy(description = emptyList()), listOf(product))
            text shouldContain "FILE_DESCRIPTION((),'2;1');\n"
        }

        "product serializes as PRODUCT(id,name,description) in declaration order" {
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(product))
            text shouldContain "#1=PRODUCT('BRK-001','Bracket','Mounting bracket');\n"
        }

        "personAndOrganization serializes as PERSON_AND_ORGANIZATION(the_person,the_organization)" {
            val person =
                personAndOrganization {
                    thePerson = "Jane Doe"
                    theOrganization = "Acme Corp"
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(person))
            text shouldContain "#1=PERSON_AND_ORGANIZATION('Jane Doe','Acme Corp');\n"
        }

        "productDefinitionFormation serializes as PRODUCT_DEFINITION_FORMATION(id,description,#ofProduct)" {
            val product = product("BRK-001") { name = "Bracket" }.getOrThrow()
            val formation =
                productDefinitionFormation("BRK-001-F") {
                    description = ""
                    ofProduct = product
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(formation))
            text shouldContain "#1=PRODUCT('BRK-001','Bracket','');\n"
            text shouldContain "#2=PRODUCT_DEFINITION_FORMATION('BRK-001-F','',#1);\n"
        }

        "productDefinition serializes as PRODUCT_DEFINITION(id,description,#formation)" {
            val product = product("BRK-001") { name = "" }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = product }.getOrThrow()
            val definition = productDefinition("BRK-001-D") { formation = builtFormation }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(definition))
            text shouldContain "#3=PRODUCT_DEFINITION('BRK-001-D','',#2);\n"
        }

        "approval serializes as APPROVAL(status,level,#authorizedBy)" {
            val person = personAndOrganization { thePerson = "Jane Doe" }.getOrThrow()
            val approval =
                approval("APPROVED") {
                    level = "A1"
                    authorizedBy = person
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(approval))
            text shouldContain "#1=PERSON_AND_ORGANIZATION('Jane Doe','');\n"
            text shouldContain "#2=APPROVAL('APPROVED','A1',#1);\n"
        }

        // The declaration order here (id, name, #relating, #related, reference_designator) is the one
        // verified against ap242-subset.exp — NOT the illustrative prompt example's (id, name,
        // reference_designator, #relating, #related), which puts reference_designator in the wrong position.
        "nextAssemblyUsageOccurrence serializes own attrs then its two refs, reference_designator last" {
            val product1 = product("BRK-001") { name = "" }.getOrThrow()
            val formation1 = productDefinitionFormation("BRK-001-F") { ofProduct = product1 }.getOrThrow()
            val relating = productDefinition("BRK-001-D") { formation = formation1 }.getOrThrow()
            val product2 = product("HSG-001") { name = "" }.getOrThrow()
            val formation2 = productDefinitionFormation("HSG-001-F") { ofProduct = product2 }.getOrThrow()
            val related = productDefinition("HSG-001-D") { formation = formation2 }.getOrThrow()
            val nauo =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = ""
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                    referenceDesignator = "RD-1"
                }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(nauo))
            text shouldContain "NEXT_ASSEMBLY_USAGE_OCCURRENCE('NAUO-001','',#3,#6,'RD-1');\n"
        }

        "an id containing an embedded single quote round-trips via '' doubling" {
            val product = product("O'Brien-01") { name = "Bracket" }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(product))
            text shouldContain "#1=PRODUCT('O''Brien-01','Bracket','');\n"
        }

        "a non-ASCII character in an attribute value throws Part21EncodingException" {
            val product = product("BRK-001") { name = "Bräcket" }.getOrThrow()
            shouldThrow<Part21EncodingException> { Part21Writer.write(testHeader(), listOf(product)) }
        }

        "an embedded control character in an attribute value throws Part21EncodingException" {
            val product = product("BRK-001") { name = "Bracket\u0001" }.getOrThrow()
            shouldThrow<Part21EncodingException> { Part21Writer.write(testHeader(), listOf(product)) }
        }

        "a root object that is not one of the six supported kstep-core types throws Part21WriteException" {
            shouldThrow<Part21WriteException> { Part21Writer.write(testHeader(), listOf("not a kstep-core entity")) }
        }

        "writing the same object graph twice produces byte-identical output" {
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = product }.getOrThrow()
            val definition = productDefinition("BRK-001-D") { formation = builtFormation }.getOrThrow()

            val first = Part21Writer.write(testHeader(), listOf(definition))
            val second = Part21Writer.write(testHeader(), listOf(definition))
            first shouldBe second
        }

        "a shared instance referenced from two different sites is written exactly once" {
            val product = product("BRK-001") { name = "" }.getOrThrow()
            val formationA = productDefinitionFormation("BRK-001-F-A") { ofProduct = product }.getOrThrow()
            val formationB = productDefinitionFormation("BRK-001-F-B") { ofProduct = product }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(formationA, formationB))
            Regex("=PRODUCT\\(").findAll(text).count() shouldBe 1
        }

        "the vararg write overload delegates to the List overload" {
            val product = product("BRK-001") { name = "" }.getOrThrow()
            Part21Writer.write(testHeader(), product) shouldBe Part21Writer.write(testHeader(), listOf(product))
        }
    })
