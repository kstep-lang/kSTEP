// export-subassembly-colour.kstep.kts -- ASM holds SUB twice (u1 at x=0, u2 at x=100), SUB holds a box (10 x 20 x 30, volume 6000) twice (u3 at x=0, u4 at x=20); only BOX has a shape (ADR-0046). u2 (a sub-assembly occurrence) is blue, u3 (a part occurrence inside every SUB) is red.
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
val sub = part("SUB-001", "Unterbaugruppe")
val box = part("BOX-001", "Box")
val u1 = usage("NAUO-1", asm, sub)
val u2 = usage("NAUO-2", asm, sub)
val u3 = usage("NAUO-3", sub, box)
val u4 = usage("NAUO-4", sub, box)
stepFile(fileName = "subassembly-colour.step", timestamp = "2026-01-01T00:00:00") {
    roots(u1, u2, u3, u4)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
    place(u1, translation = vec(0.0, 0.0, 0.0))
    place(u2, translation = vec(100.0, 0.0, 0.0))
    place(u3, translation = vec(0.0, 0.0, 0.0))
    place(u4, translation = vec(20.0, 0.0, 0.0))
    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))
    styleInstance(u3, StepColour.rgb(1.0, 0.0, 0.0))
}
