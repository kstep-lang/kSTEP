// export-golden-multi-solid.kstep.kts -- two bodies in one part with a fixed timestamp. Its byte-exact output (export-golden-multi-solid.step) was generated BEFORE ADR-0039 and guards that unstyled exports stay byte-identical (Welle S).
// Expected: one SHAPE_REPRESENTATION with 2 MANIFOLD_SOLID_BREPs.
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()
val defCtx =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = appCtx
        lifeCycleStage = "design"
    }.getOrThrow()
val part =
    product("TWO-DIFF") {
        name = "Two different bodies"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("TWO-DIFF-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("TWO-DIFF-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val body = OcctKernel.makeBox(10.0, 20.0, 30.0)
val small = OcctKernel.makeBox(4.0, 4.0, 4.0)
val shape = OcctKernel.compound(listOf(body, mirror(small, MirrorPlane.x(-5.0))))

stepFile(fileName = "golden-multi-solid.step", timestamp = "2026-01-01T00:00:00") {
    root(definition)
    shape(definition, shape)
}
