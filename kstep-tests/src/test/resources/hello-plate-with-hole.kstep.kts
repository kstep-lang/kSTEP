// hello-plate-with-hole.kstep.kts -- a 40x40x10 plate with an 8 mm through-hole, built with the
// Welle-J geometry operations (OcctKernel.hole = makeCylinder + cut, ADR-0029).
//
// Run: kstep render hello-plate-with-hole.kstep.kts --format svg   ->  writes hello-plate-with-hole.svg
//      kstep render hello-plate-with-hole.kstep.kts --format glb   ->  writes hello-plate-with-hole.glb
// Expected: volume 16000 - 160*pi = 15497.346, 7 faces.
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
    product("PLATE-001") {
        name = "Plate with hole"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("PLATE-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("PLATE-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Intermediate shapes (the plate blank, the drill tool) are deliberately not closed by hand: the
// OcctShape Cleaner releases them when they become unreachable. Only the final shape is registered
// via shape(...), and `kstep render`/`kstep export` close every registered shape themselves.
val blank = OcctKernel.makeBox(40.0, 40.0, 10.0)
// Through-hole d=8 at (20, 20), drilled downwards from the top face (z = 10), depth 10.
// Volume = 40*40*10 - pi*4^2*10 = 16000 - 160*pi = 15497.346
val shape = OcctKernel.hole(blank, diameter = 8.0, depth = 10.0, x = 20.0, y = 20.0, z = 10.0)

stepFile(fileName = "hello-plate-with-hole.step") {
    root(definition)
    shape(definition, shape)
}
