// hello-draft-invalid.kstep.kts -- a draft of the top face about a neutral plane parallel to it (Welle Q2, ADR-0040).
// Run: kstep export hello-draft-invalid.kstep.kts  ->  KSTEP-S-003, "OCCT failed to draft shape" with the Draft_ErrorStatus.
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
    product("DRAFT-BAD") {
        name = "Invalid draft"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("DRAFT-BAD-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("DRAFT-BAD-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = draft(box, Faces.atMax(Direction3.Z), 5.0, NeutralPlane(Point3(0.0, 0.0, 0.0), Direction3.Z))

stepFile(fileName = "draft-invalid.step") {
    root(definition)
    shape(definition, shape)
}
