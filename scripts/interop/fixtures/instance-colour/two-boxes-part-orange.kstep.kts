// Base for the ADR-0044 gate fixtures: one box used twice, instance 1 at the origin, instance 2 at x = 50 (no colour).
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
fun part(id: String, name: String): ProductDefinition {
    val p = product(id) { this.name = name; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val f = productDefinitionFormation("$id-F") { ofProduct = p }.getOrThrow()
    return productDefinition("$id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
}
fun usage(id: String, parent: ProductDefinition, child: ProductDefinition) =
    nextAssemblyUsageOccurrence(id) { name = id; relatingProductDefinition = parent; relatedProductDefinition = child }.getOrThrow()
val assembly = part("ASM-001", "Baugruppe")
val box = part("BOX-001", "Box")
val u1 = usage("NAUO-1", assembly, box)
val u2 = usage("NAUO-2", assembly, box)
stepFile(fileName = "two-orange.step", timestamp = "2026-01-01T00:00:00") {
    roots(u1, u2)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0))
    place(u1, translation = vec(0.0, 0.0, 0.0))
    place(u2, translation = vec(50.0, 0.0, 0.0))
}
