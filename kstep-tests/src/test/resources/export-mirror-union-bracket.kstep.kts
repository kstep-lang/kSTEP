// export-mirror-union-bracket.kstep.kts -- A symmetric bracket: an L-profile (area 600) extruded by 20, mirrored in
// x = 0 along its outer edge and fused with the original (Welle P, ADR-0036). The two halves share the face x = 0.
// Expected: volume 24000, bounding box x in [-40, 40], y in [0, 30], z in [0, 20], one solid.
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
    product("MIRROR-BRACKET") {
        name = "Mirrored bracket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("MIRROR-BRACKET-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("MIRROR-BRACKET-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val profile =
    listOf(
        ProfilePoint(0.0, 0.0),
        ProfilePoint(40.0, 0.0),
        ProfilePoint(40.0, 10.0),
        ProfilePoint(10.0, 10.0),
        ProfilePoint(10.0, 30.0),
        ProfilePoint(0.0, 30.0),
    )
val half = OcctKernel.extrudeProfile(profile, 20.0)
val shape = OcctKernel.union(half, mirror(half, MirrorPlane.YZ))

stepFile(fileName = "mirror-union-bracket.step") {
    root(definition)
    shape(definition, shape)
}
