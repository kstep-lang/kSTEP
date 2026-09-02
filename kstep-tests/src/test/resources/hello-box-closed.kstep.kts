// hello-box-closed.kstep.kts -- registers a shape that the script itself has ALREADY closed via
// `use { }` before stepFile even runs (ADR-0011's Stolperfalle 7: `OcctShape.triangulate()`
// throws `IllegalStateException` for a closed shape). `kstep render` must recognize this and fall
// back to a "notice" (fallbackReason = shape_closed_by_script) instead of propagating the
// exception -- this fixture only makes sense to run on a machine where OCCT IS available (see
// hello-box-guarded.kstep.kts for the portable alternative).

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
    product("CBOX-001") {
        name = "Closed Box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("CBOX-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("CBOX-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(5.0, 5.0, 5.0)
box.close()

stepFile(fileName = "hello-box-closed.step") {
    root(definition)
    shape(definition, box)
}
