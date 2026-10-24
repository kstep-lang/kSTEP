// export-draft-box.kstep.kts -- the four side faces of a 40x30x20 box drafted by 5 degrees about z = 0, pull +Z (Welle Q2,
// ADR-0040). Expected: exact volume 24000 - 28000*tan(5deg) + 32000/3*tan(5deg)^2 = 21631.962928, 6 faces, 1 solid.
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
    product("DRAFT-BOX") {
        name = "Drafted box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("DRAFT-BOX-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("DRAFT-BOX-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val sides = Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X) or Faces.normal(Direction3.Y) or Faces.normal(Direction3.NEG_Y)
val shape = draft(box, sides, 5.0, NeutralPlane(Point3(0.0, 0.0, 0.0), Direction3.Z))

stepFile(fileName = "draft-box.step") {
    root(definition)
    shape(definition, shape)
}
