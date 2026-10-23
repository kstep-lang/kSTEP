// export-shell-box-outward.kstep.kts -- the same box, the wall grows OUTWARD (Welle Q, ADR-0037).
// Expected: volume 44*34*22 - 40*30*20 = 8912, 11 faces, 1 solid, box x -2..42, y -2..32, z -2..20.
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
    product("SHELL-OUT") {
        name = "Box with an outward wall"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-OUT-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-OUT-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = shell(OcctKernel.makeBox(40.0, 30.0, 20.0), Faces.atMax(Direction3.Z), 2.0, ShellDirection.OUTWARD)

stepFile(fileName = "shell-box-outward.step") {
    root(definition)
    shape(definition, shape)
}
