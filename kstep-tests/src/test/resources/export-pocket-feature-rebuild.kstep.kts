// export-pocket-feature-rebuild.kstep.kts -- a pocket in a feature history; the box is first 20 high, then rebuilt
// with height 30 (Welle Q2, ADR-0040). Expected for the rebuilt shape: 40*30*30 - 10*10*5 = 35500, 11 faces, 1 solid.
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.PocketFeature
import dev.kstep.geometry.feature.SelectFaces

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
    product("POCKET-REB") {
        name = "Parametric pocket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCKET-REB-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCKET-REB-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val history = FeatureHistory()
history.add(BoxFeature(40.0, 30.0, 20.0))
history.add(PocketFeature(0, SelectFaces(Faces.atMax(Direction3.Z), expected = 1), PocketProfile.Rectangle(10.0, 10.0), PocketDepth.Blind(5.0)))
history.update(0) { (it as BoxFeature).copy(dz = 30.0) }
// The history holds no shapes; they exist only for the lifetime of the rebuild result.
val shape = history.rebuild().final

stepFile(fileName = "pocket-feature-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
