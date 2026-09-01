package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinitionContext
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

private fun buildAppCtx(): ApplicationContext = applicationContext { application = "config control" }.getOrThrow()

private fun buildProductCtx(appCtx: ApplicationContext = buildAppCtx()): ProductContext =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()

private fun buildProductDefCtx(appCtx: ApplicationContext = buildAppCtx()): ProductDefinitionContext =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = appCtx
        lifeCycleStage = "design"
    }.getOrThrow()

/** Finds the "#N=ENTITY_NAME(...)" line for [entityName] and returns its full argument text. */
private fun String.instanceArgsOf(entityName: String): String {
    val match =
        Regex("""#\d+=$entityName\((.*)\);""").find(this)
            ?: error("no $entityName instance line found in:\n$this")
    return match.groupValues[1]
}

class Part21WriterTest :
    StringSpec({
        "header statements serialize all fields in FILE_DESCRIPTION/FILE_NAME/FILE_SCHEMA order" {
            val appCtx = buildAppCtx()
            val prodCtx = buildProductCtx(appCtx)
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                    frameOfReference = setOf(prodCtx)
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
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val text = Part21Writer.write(testHeader().copy(description = emptyList()), listOf(product))
            text shouldContain "FILE_DESCRIPTION((),'2;1');\n"
        }

        "applicationContext serializes as APPLICATION_CONTEXT(application)" {
            val text = Part21Writer.write(testHeader(), listOf(buildAppCtx()))
            text.instanceArgsOf("APPLICATION_CONTEXT") shouldBe "'config control'"
        }

        "productContext serializes as PRODUCT_CONTEXT(name,#frameOfReference,disciplineType)" {
            val text = Part21Writer.write(testHeader(), listOf(buildProductCtx()))
            text.instanceArgsOf("PRODUCT_CONTEXT") shouldBe "'engineering',#1,'mechanical'"
        }

        "organization serializes as ORGANIZATION(id,name,description) with \$ for unset id/description" {
            val org = organization { name = "Acme" }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(org))
            text.instanceArgsOf("ORGANIZATION") shouldBe "\$,'Acme',\$"
        }

        "person serializes with \$ for every unset optional field and (...) for a set LIST OF label" {
            val p =
                person("P-001") {
                    lastName = "Doe"
                    middleNames = listOf("Alice", "Bob")
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(p))
            text.instanceArgsOf("PERSON") shouldBe "'P-001','Doe',\$,('Alice','Bob'),\$,\$"
        }

        "product serializes as PRODUCT(id,name,description,(#frameOfReference...)) in declaration order" {
            val appCtx = buildAppCtx()
            val prodCtx = buildProductCtx(appCtx)
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(product))
            text.instanceArgsOf("PRODUCT") shouldBe "'BRK-001','Bracket','Mounting bracket',(#2)"
            // #2 must indeed be the PRODUCT_CONTEXT — proves the reference list, not just its shape.
            text shouldContain "#2=PRODUCT_CONTEXT("
        }

        "personAndOrganization serializes as PERSON_AND_ORGANIZATION(#thePerson,#theOrganization)" {
            val person = person("P-001") { lastName = "Doe" }.getOrThrow()
            val org = organization { name = "Acme Corp" }.getOrThrow()
            val result =
                personAndOrganization {
                    thePerson = person
                    theOrganization = org
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(result))
            text.instanceArgsOf("PERSON_AND_ORGANIZATION") shouldBe "#1,#2"
            text shouldContain "#1=PERSON("
            text shouldContain "#2=ORGANIZATION("
        }

        "productDefinitionFormation serializes as PRODUCT_DEFINITION_FORMATION(id,description,#ofProduct)" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formation =
                productDefinitionFormation("BRK-001-F") {
                    ofProduct = product
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(formation))
            text.instanceArgsOf("PRODUCT_DEFINITION_FORMATION") shouldContain "'BRK-001-F',\$,#"
            // appCtx=#1, prodCtx=#2, product=#3, formation=#4 — post-order DFS from formation.
            text.instanceArgsOf("PRODUCT") shouldBe "'BRK-001','Bracket',\$,(#2)"
        }

        "productDefinition serializes as PRODUCT_DEFINITION(id,description,#formation,#frameOfReference)" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = product }.getOrThrow()
            val defCtx = buildProductDefCtx()
            val definition =
                productDefinition("BRK-001-D") {
                    formation = builtFormation
                    frameOfReference = defCtx
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(definition))
            val args = text.instanceArgsOf("PRODUCT_DEFINITION").split(",")
            args[0] shouldBe "'BRK-001-D'"
            args[1] shouldBe "\$"
            text shouldContain "${args[2]}=PRODUCT_DEFINITION_FORMATION("
            text shouldContain "${args[3]}=PRODUCT_DEFINITION_CONTEXT("
        }

        "approval serializes as APPROVAL(#status,level)" {
            val status = approvalStatus { name = "approved" }.getOrThrow()
            val approval =
                approval {
                    this.status = status
                    level = "A1"
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(approval))
            text.instanceArgsOf("APPROVAL") shouldBe "#1,'A1'"
            text shouldContain "#1=APPROVAL_STATUS("
        }

        "nextAssemblyUsageOccurrence serializes own attrs then its two refs, reference_designator last" {
            val prodCtx = buildProductCtx()
            val defCtx = buildProductDefCtx()
            val product1 =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formation1 = productDefinitionFormation("BRK-001-F") { ofProduct = product1 }.getOrThrow()
            val relating =
                productDefinition("BRK-001-D") {
                    formation = formation1
                    frameOfReference = defCtx
                }.getOrThrow()
            val product2 =
                product("HSG-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formation2 = productDefinitionFormation("HSG-001-F") { ofProduct = product2 }.getOrThrow()
            val related =
                productDefinition("HSG-001-D") {
                    formation = formation2
                    frameOfReference = defCtx
                }.getOrThrow()
            val nauo =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = ""
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                    referenceDesignator = "RD-1"
                }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(nauo))
            val args = text.instanceArgsOf("NEXT_ASSEMBLY_USAGE_OCCURRENCE").split(",")
            args[0] shouldBe "'NAUO-001'"
            args[1] shouldBe "''"
            args[2] shouldBe "\$" // description, never set
            text shouldContain "${args[3]}=PRODUCT_DEFINITION("
            text shouldContain "${args[4]}=PRODUCT_DEFINITION("
            args[5] shouldBe "'RD-1'"
        }

        "nextAssemblyUsageOccurrence with an unset reference_designator serializes it as \$" {
            val prodCtx = buildProductCtx()
            val defCtx = buildProductDefCtx()
            val product1 =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formation1 = productDefinitionFormation("BRK-001-F") { ofProduct = product1 }.getOrThrow()
            val relating =
                productDefinition("BRK-001-D") {
                    formation = formation1
                    frameOfReference = defCtx
                }.getOrThrow()
            val nauo =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = ""
                    relatingProductDefinition = relating
                    relatedProductDefinition = relating
                }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(nauo))
            text.instanceArgsOf("NEXT_ASSEMBLY_USAGE_OCCURRENCE").split(",").last() shouldBe "\$"
        }

        "an id containing an embedded single quote round-trips via '' doubling" {
            val prodCtx = buildProductCtx()
            val product =
                product("O'Brien-01") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val text = Part21Writer.write(testHeader(), listOf(product))
            text.instanceArgsOf("PRODUCT") shouldContain "'O''Brien-01'"
        }

        "a non-ASCII character in an attribute value throws Part21EncodingException" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = "Bräcket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            shouldThrow<Part21EncodingException> { Part21Writer.write(testHeader(), listOf(product)) }
        }

        "an embedded control character in an attribute value throws Part21EncodingException" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            shouldThrow<Part21EncodingException> { Part21Writer.write(testHeader(), listOf(product)) }
        }

        "a reverse solidus in an attribute value throws Part21EncodingException rather than being written unescaped" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = "SAFE\\X2\\04100420\\X0\\"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            shouldThrow<Part21EncodingException> { Part21Writer.write(testHeader(), listOf(product)) }
        }

        "a reverse solidus is rejected in an id, a description, and a LIST OF label element" {
            val prodCtx = buildProductCtx()

            shouldThrow<Part21EncodingException> {
                val badProduct =
                    product("BRK\\001") {
                        name = "Bracket"
                        frameOfReference = setOf(prodCtx)
                    }.getOrThrow()
                Part21Writer.write(testHeader(), listOf(badProduct))
            }
            shouldThrow<Part21EncodingException> {
                val badProduct =
                    product("BRK-001") {
                        name = "Bracket"
                        description = "back\\slash"
                        frameOfReference = setOf(prodCtx)
                    }.getOrThrow()
                Part21Writer.write(testHeader(), listOf(badProduct))
            }
            shouldThrow<Part21EncodingException> {
                val badPerson =
                    person("P-001") {
                        lastName = "Doe"
                        middleNames = listOf("Al\\ice")
                    }.getOrThrow()
                Part21Writer.write(testHeader(), listOf(badPerson))
            }
        }

        "a root object that is not one of the twelve supported kstep-core types throws Part21WriteException" {
            shouldThrow<Part21WriteException> { Part21Writer.write(testHeader(), listOf("not a kstep-core entity")) }
        }

        "writing the same object graph twice produces byte-identical output — including a Set-valued attribute" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = product }.getOrThrow()
            val defCtx = buildProductDefCtx()
            val definition =
                productDefinition("BRK-001-D") {
                    formation = builtFormation
                    frameOfReference = defCtx
                }.getOrThrow()

            val first = Part21Writer.write(testHeader(), listOf(definition))
            val second = Part21Writer.write(testHeader(), listOf(definition))
            first shouldBe second
        }

        "a shared instance referenced from two different sites is written exactly once" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formationA = productDefinitionFormation("BRK-001-F-A") { ofProduct = product }.getOrThrow()
            val formationB = productDefinitionFormation("BRK-001-F-B") { ofProduct = product }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(formationA, formationB))
            Regex("=PRODUCT\\(").findAll(text).count() shouldBe 1
        }

        "a shared product_context referenced by two different products is written exactly once" {
            val prodCtx = buildProductCtx()
            val productA =
                product("A") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val productB =
                product("B") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()

            val text = Part21Writer.write(testHeader(), listOf(productA, productB))
            Regex("=PRODUCT_CONTEXT\\(").findAll(text).count() shouldBe 1
        }

        "the vararg write overload delegates to the List overload" {
            val prodCtx = buildProductCtx()
            val product =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            Part21Writer.write(testHeader(), product) shouldBe Part21Writer.write(testHeader(), listOf(product))
        }
    })
