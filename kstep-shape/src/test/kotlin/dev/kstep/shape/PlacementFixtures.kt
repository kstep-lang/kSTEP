package dev.kstep.shape

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition

// Shared fixtures for the placement tests: pure kstep-core entities, no OCCT.
internal object PlacementFixtures {
    private val appCtx = applicationContext { application = "config control" }.getOrThrow()
    private val prodCtx =
        productContext {
            name = "engineering"
            frameOfReference = appCtx
            disciplineType = "mechanical"
        }.getOrThrow()
    private val defCtx =
        productDefinitionContext {
            name = "engineering"
            frameOfReference = appCtx
            lifeCycleStage = "design"
        }.getOrThrow()

    fun pd(id: String): ProductDefinition {
        val p =
            product(id) {
                name = id
                frameOfReference = setOf(prodCtx)
            }.getOrThrow()
        val f = productDefinitionFormation("$id-F") { ofProduct = p }.getOrThrow()
        return productDefinition("$id-D") {
            formation = f
            frameOfReference = defCtx
        }.getOrThrow()
    }

    fun usage(
        id: String,
        parent: ProductDefinition,
        child: ProductDefinition,
    ): NextAssemblyUsageOccurrence =
        nextAssemblyUsageOccurrence(id) {
            name = id
            relatingProductDefinition = parent
            relatedProductDefinition = child
        }.getOrThrow()
}
