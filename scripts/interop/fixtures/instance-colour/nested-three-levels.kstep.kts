// ADR-0046 gate H5: ASM uses MID once (v1), MID uses SUB twice (v2, v3), SUB uses BOX once (v4).
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

val asm = part("ASM-001", "Baugruppe")
val mid = part("MID-001", "Mittelbaugruppe")
val sub = part("SUB-001", "Unterbaugruppe")
val box = part("BOX-001", "Box")
val v1 = usage("NAUO-1", asm, mid)
val v2 = usage("NAUO-2", mid, sub)
val v3 = usage("NAUO-3", mid, sub)
val v4 = usage("NAUO-4", sub, box)
stepFile(fileName = "nested-three.step", timestamp = "2026-01-01T00:00:00") {
    roots(v1, v2, v3, v4)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
    place(v1, translation = vec(0.0, 0.0, 0.0))
    place(v2, translation = vec(0.0, 0.0, 0.0))
    place(v3, translation = vec(50.0, 0.0, 0.0))
    place(v4, translation = vec(0.0, 0.0, 0.0))
}
