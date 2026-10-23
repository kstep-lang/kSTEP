// export-shell-cylinder.kstep.kts -- cylinder r=10, h=20, top cap open, 1 mm wall (Welle Q, ADR-0037).
// Expected: volume pi*(10^2*20 - 9^2*19) = 461*pi = 1448.2742, 5 faces, 1 solid, box x -10..10, y -10..10, z 0..20.
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
    product("SHELL-CYL") {
        name = "Hollowed cylinder"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-CYL-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-CYL-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = shell(OcctKernel.makeCylinder(10.0, 20.0), Faces.normal(Direction3.Z), 1.0)

stepFile(fileName = "shell-cylinder.step") {
    root(definition)
    shape(definition, shape)
}
