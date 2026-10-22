package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.descriptionAttribute
import dev.kstep.core.ap242.idAttribute
import dev.kstep.core.ap242.nameAttribute
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.ap242.productDefinitionOccurrence
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOrReference

/** Builds product definitions, occurrences and usages over one shared context set. */
internal class AssemblyTestModel {
    val ac: ApplicationContext = applicationContext { application = "config control" }.getOrThrow()
    private val pc =
        productContext {
            name = "e"
            frameOfReference = ac
            disciplineType = "m"
        }.getOrThrow()
    private val pdc =
        productDefinitionContext {
            name = "e"
            frameOfReference = ac
            lifeCycleStage = "d"
        }.getOrThrow()

    fun pd(id: String): ProductDefinition {
        val prod =
            product("prod-$id") {
                name = id
                frameOfReference = setOf(pc)
            }.getOrThrow()
        val formation = productDefinitionFormation("$id-F") { ofProduct = prod }.getOrThrow()
        return productDefinition(id) {
            this.formation = formation
            frameOfReference = pdc
        }.getOrThrow()
    }

    fun pdo(
        id: String,
        definition: ProductDefinitionOrReference? = null,
    ): ProductDefinitionOccurrence = productDefinitionOccurrence(id) { this.definition = definition }.getOrThrow()

    fun usage(
        id: String,
        relating: ProductDefinitionOrReference,
        related: ProductDefinitionOrReference,
        referenceDesignator: String? = null,
    ): NextAssemblyUsageOccurrence =
        nextAssemblyUsageOccurrence(id) {
            name = "usage $id"
            relatingProductDefinition = relating
            relatedProductDefinition = related
            this.referenceDesignator = referenceDesignator
        }.getOrThrow()

    fun nameOf(
        item: ProductDefinition,
        value: String,
    ) = nameAttribute {
        attributeValue = value
        namedItem = item
    }.getOrThrow()

    fun personAndOrganization(): PersonAndOrganization =
        personAndOrganization {
            thePerson = person("x") { lastName = "Doe" }.getOrThrow()
            theOrganization = organization { name = "ACME" }.getOrThrow()
        }.getOrThrow()

    fun descriptionOf(
        item: PersonAndOrganization,
        value: String,
    ) = descriptionAttribute {
        attributeValue = value
        describedItem = item
    }.getOrThrow()

    fun idOf(
        item: ApplicationContext,
        value: String,
    ) = idAttribute {
        attributeValue = value
        identifiedItem = item
    }.getOrThrow()
}
