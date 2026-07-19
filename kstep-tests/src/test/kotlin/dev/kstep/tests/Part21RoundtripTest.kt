package dev.kstep.tests

import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinitionFormation
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
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

class Part21RoundtripTest :
    StringSpec({
        "a 2-part-plus-assembly product structure roundtrips losslessly through export + parse" {
            val bracket =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val bracketFormation =
                productDefinitionFormation("BRK-001-F") {
                    description = ""
                    ofProduct = bracket
                }.getOrThrow()
            val bracketDefinition =
                productDefinition("BRK-001-D") {
                    description = ""
                    formation = bracketFormation
                }.getOrThrow()

            val housing =
                product("HSG-001") {
                    name = "Housing"
                    description = "Enclosure housing"
                }.getOrThrow()
            val housingFormation =
                productDefinitionFormation("HSG-001-F") {
                    description = ""
                    ofProduct = housing
                }.getOrThrow()
            val housingDefinition =
                productDefinition("HSG-001-D") {
                    description = ""
                    formation = housingFormation
                }.getOrThrow()

            val nauo =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = ""
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
            val bracket =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val exported = Part21Writer.write(testHeader(), listOf(bracket))
            val result = Part21Reader.read(exported)

            result.isFullySuccessful shouldBe true
            val roundtripped =
                result.instances.values
                    .filterIsInstance<Product>()
                    .single()
            roundtripped shouldBe bracket
        }

        // The observable form of "shared instance reconstructed as shared": data class equals() is
        // structural, so two independently-built-but-equal Products would satisfy `==` regardless of
        // whether the writer deduplicated them — that assertion alone would prove nothing about
        // sharing. What IS observable is (a) the writer emits exactly one #N=PRODUCT(...) line for the
        // one shared instance, and (b) the two roundtripped ofProduct references are the *same* object
        // (===), not merely two equal-but-distinct ones.
        "a shared Product reference is written once and reconstructed as the same shared instance on read" {
            val sharedProduct = product("BRK-001") { name = "Bracket" }.getOrThrow()
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
    })
