// export-shell-feature-rebuild.kstep.kts -- a shell in a feature history; the box is first 20 high, then rebuilt
// with height 30 (Welle Q, ADR-0037). Expected for the rebuilt shape: 40*30*30 - 36*26*28 = 9792, 11 faces, 1 solid.
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.ShellFeature

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
    product("SHELL-REB") {
        name = "Parametric shell"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-REB-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-REB-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val history = FeatureHistory()
history.add(BoxFeature(40.0, 30.0, 20.0))
history.add(ShellFeature(0, SelectFaces(Faces.atMax(Direction3.Z), expected = 1), 2.0))
history.update(0) { (it as BoxFeature).copy(dz = 30.0) }
// The history holds no shapes; they exist only for the lifetime of the rebuild result.
val shape = history.rebuild().final

stepFile(fileName = "shell-feature-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
