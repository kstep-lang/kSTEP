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
import dev.kstep.generated.ap242v1.Approval
import dev.kstep.generated.ap242v1.ApprovalStatus
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21Writer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun testHeader(): Part21Header =
    Part21Header(
        fileName = "assembly.step",
        timestamp = "2026-07-19T12:00:00",
        schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
        description = listOf("kSTEP Part-21 roundtrip test fixture"),
        author = listOf("kSTEP"),
        organization = listOf("kSTEP"),
    )

private fun buildAppCtx(): ApplicationContext = applicationContext { application = "config control" }.getOrThrow()

private fun buildProductCtx(appCtx: ApplicationContext): ProductContext =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()

private fun buildProductDefCtx(appCtx: ApplicationContext): ProductDefinitionContext =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = appCtx
        lifeCycleStage = "design"
    }.getOrThrow()

class Part21RoundtripTest :
    StringSpec({
        "a 2-part-plus-assembly product structure roundtrips losslessly through export + parse" {
            val appCtx = buildAppCtx()
            val prodCtx = buildProductCtx(appCtx)
            val defCtx = buildProductDefCtx(appCtx)

            val bracket =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
            val bracketDefinition =
                productDefinition("BRK-001-D") {
                    formation = bracketFormation
                    frameOfReference = defCtx
                }.getOrThrow()

            val housing =
                product("HSG-001") {
                    name = "Housing"
                    description = "Enclosure housing"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val housingFormation = productDefinitionFormation("HSG-001-F") { ofProduct = housing }.getOrThrow()
            val housingDefinition =
                productDefinition("HSG-001-D") {
                    formation = housingFormation
                    frameOfReference = defCtx
                }.getOrThrow()

            val nauo =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = "usage"
                    relatingProductDefinition = housingDefinition
                    relatedProductDefinition = bracketDefinition
                    referenceDesignator = "RD-1"
                }.getOrThrow()

            val header = testHeader()
            val exported = Part21Writer.write(header, listOf(nauo))
            val result = Part21Reader.read(exported)

            result.isFullySuccessful shouldBe true
            result.header shouldBe header
            val roundtripped =
                result.instances.values
                    .filterIsInstance<NextAssemblyUsageOccurrence>()
                    .single()
            roundtripped shouldBe nauo
        }

        "a single leaf entity with no references roundtrips" {
            val a = applicationContext { application = "config control" }.getOrThrow()
            val exported = Part21Writer.write(testHeader(), listOf(a))
            val result = Part21Reader.read(exported)

            result.isFullySuccessful shouldBe true
            val roundtripped =
                result.instances.values
                    .filterIsInstance<ApplicationContext>()
                    .single()
            roundtripped shouldBe a
        }

        // The observable form of "shared instance reconstructed as shared": data class equals() is
        // structural, so two independently-built-but-equal Products would satisfy `==` regardless of
        // whether the writer deduplicated them — that assertion alone would prove nothing about
        // sharing. What IS observable is (a) the writer emits exactly one #N=PRODUCT(...) line for the
        // one shared instance, and (b) the two roundtripped ofProduct references are the *same* object
        // (===), not merely two equal-but-distinct ones.
        "a shared Product reference is written once and reconstructed as the same shared instance on read" {
            val appCtx = buildAppCtx()
            val prodCtx = buildProductCtx(appCtx)
            val sharedProduct =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formationA =
                productDefinitionFormation("BRK-001-F-A") {
                    description = "A"
                    ofProduct = sharedProduct
                }.getOrThrow()
            val formationB =
                productDefinitionFormation("BRK-001-F-B") {
                    description = "B"
                    ofProduct = sharedProduct
                }.getOrThrow()

            val exported = Part21Writer.write(testHeader(), listOf(formationA, formationB))
            Regex("=PRODUCT\\(").findAll(exported).count() shouldBe 1

            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true
            val formations = result.instances.values.filterIsInstance<ProductDefinitionFormation>()
            formations shouldHaveSize 2
            val roundtrippedA = formations.single { it.id == "BRK-001-F-A" }
            val roundtrippedB = formations.single { it.id == "BRK-001-F-B" }
            (roundtrippedA.ofProduct === roundtrippedB.ofProduct) shouldBe true
        }

        "a model containing all twelve AP242 entity types roundtrips losslessly, including \$-unset optional fields" {
            val appCtx = buildAppCtx()
            val prodCtx = buildProductCtx(appCtx)
            val defCtx = buildProductDefCtx(appCtx)
            val approvalStatus = approvalStatus { name = "approved" }.getOrThrow()
            val person =
                person("P-001") {
                    lastName = "Doe"
                    middleNames = listOf("Q")
                }.getOrThrow()
            val organization = organization { name = "Acme" }.getOrThrow()
            val personAndOrg =
                personAndOrganization {
                    thePerson = person
                    theOrganization = organization
                }.getOrThrow()
            val approvalEntity =
                approval {
                    status = approvalStatus
                    level = "A1"
                }.getOrThrow()
            val product =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = product }.getOrThrow()
            val definition =
                productDefinition("BRK-001-D") {
                    formation = builtFormation
                    frameOfReference = defCtx
                }.getOrThrow()
            // No reference_designator set -- exercises the $ round-trip for a genuinely optional field.
            val nauo =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    name = "usage"
                    relatingProductDefinition = definition
                    relatedProductDefinition = definition
                }.getOrThrow()

            val exported = Part21Writer.write(testHeader(), listOf(nauo, personAndOrg, approvalEntity))
            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true

            result.instances.values
                .filterIsInstance<ApplicationContext>()
                .single() shouldBe appCtx
            result.instances.values
                .filterIsInstance<ProductContext>()
                .single() shouldBe prodCtx
            result.instances.values
                .filterIsInstance<ProductDefinitionContext>()
                .single() shouldBe defCtx
            result.instances.values
                .filterIsInstance<ApprovalStatus>()
                .single() shouldBe approvalStatus
            result.instances.values
                .filterIsInstance<Person>()
                .single() shouldBe person
            result.instances.values
                .filterIsInstance<Organization>()
                .single() shouldBe organization
            result.instances.values
                .filterIsInstance<Product>()
                .single() shouldBe product
            result.instances.values
                .filterIsInstance<ProductDefinitionFormation>()
                .single() shouldBe builtFormation
            result.instances.values
                .filterIsInstance<ProductDefinition>()
                .single() shouldBe definition
            result.instances.values
                .filterIsInstance<NextAssemblyUsageOccurrence>()
                .single() shouldBe nauo
            result.instances.values
                .filterIsInstance<Approval>()
                .single() shouldBe approvalEntity
            result.instances.values
                .filterIsInstance<PersonAndOrganization>()
                .single() shouldBe personAndOrg
        }
    })
