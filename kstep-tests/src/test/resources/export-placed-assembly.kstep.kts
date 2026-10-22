// export-placed-assembly.kstep.kts -- a placed assembly for `kstep export` (ADR-0028): a shape-less
// root assembly "Baugruppe" containing a housing, a bracket (moved and turned 90 degrees about Z)
// and one screw part used twice at different places. Expected global result: four solids, total
// volume 17384, bounding box [0,40] x [0,40] x [0,22] (mm).
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

fun part(id: String, name: String): ProductDefinition {
    val p = product(id) { this.name = name; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val f = productDefinitionFormation("$id-F") { ofProduct = p }.getOrThrow()
    return productDefinition("$id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
}

fun usage(id: String, parent: ProductDefinition, child: ProductDefinition) =
    nextAssemblyUsageOccurrence(id) {
        name = id
        relatingProductDefinition = parent
        relatedProductDefinition = child
    }.getOrThrow()

val assembly = part("ASM-001", "Baugruppe")
val housing = part("HSG-001", "Gehaeuse")
val bracket = part("BRK-001", "Halter")
val screw = part("SCR-001", "Schraube")

val nauoHousing = usage("NAUO-H", assembly, housing)
val nauoBracket = usage("NAUO-B", assembly, bracket)
val nauoScrew1 = usage("NAUO-S1", assembly, screw)
val nauoScrew2 = usage("NAUO-S2", assembly, screw)

stepFile(fileName = "placed-assembly.step") {
    roots(nauoHousing, nauoBracket, nauoScrew1, nauoScrew2)
    shape(housing, OcctKernel.makeBox(40.0, 40.0, 10.0))
    shape(bracket, OcctKernel.makeBox(10.0, 20.0, 5.0))
    shape(screw, OcctKernel.makeBox(4.0, 4.0, 12.0))
    // NAUO-H stays at the origin (identity by default).
    place(nauoBracket, translation = vec(30.0, 5.0, 10.0), rotation = Rotation.about(Vec3.Z, 90.0))
    place(nauoScrew1, translation = vec(20.0, 30.0, 10.0))
    place(nauoScrew2, translation = vec(8.0, 30.0, 10.0))
}
