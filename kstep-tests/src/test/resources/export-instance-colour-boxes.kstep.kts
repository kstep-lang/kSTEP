// export-instance-colour-boxes.kstep.kts -- one box (10 x 20 x 30, volume 6000) used twice, instance 1 at the origin in red, instance 2 at x = 50 in blue (ADR-0044).
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
stepFile(fileName = "instance-colour-boxes.step", timestamp = "2026-01-01T00:00:00") {
    roots(u1, u2)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
    place(u1, translation = vec(0.0, 0.0, 0.0))
    place(u2, translation = vec(50.0, 0.0, 0.0))
    styleInstance(u1, StepColour.rgb(1.0, 0.0, 0.0))
    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))
}
