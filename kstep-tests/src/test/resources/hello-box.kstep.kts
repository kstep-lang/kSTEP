// hello-box.kstep.kts -- registers a 10x20x30 OCCT box as this script's geometry, previewable
// via `kstep render hello-box.kstep.kts`. No imports needed: OcctKernel/ShapeAssignment are
// among the kSTEP script definition's defaultImports (dev.kstep.script.KStepScriptCompilationConfiguration),
// added in kSTEP's headless-preview-rendering wave (see docs/adr/ADR-0011-headless-preview-rendering.adoc).
//
// Run: kstep render hello-box.kstep.kts  ->  writes hello-box.svg

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
    product("BOX-001") {
        name = "Box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOX-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOX-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Deliberately NOT wrapped in `use { }` -- the shape must stay open until `kstep render`
// triangulates it for the preview (see hello-box-guarded.kstep.kts for the self-closed
// counter-example, and ADR-0011's Stolperfalle 7). `kstep render` closes every registered
// shape itself once rendering is done.
val box = OcctKernel.makeBox(10.0, 20.0, 30.0)

stepFile(fileName = "hello-box.step") {
    root(definition)
    shape(definition, box)
}
