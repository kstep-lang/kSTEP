// export-feature-history-undo.kstep.kts -- undo/redo over a feature history (ADR-0033).
// Box 40x20x10, vertical edges rounded r = 2, through hole d = 8; then undo the hole, redo it, widen the hole to d = 10.
// Exported end state: 7965.664 - pi*5^2*10 = 7180.266, 11 faces.
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedFilletFeature

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
    product("HISTORY-001") {
        name = "Feature history with undo and redo"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("HISTORY-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("HISTORY-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val history = FeatureHistory()
history.add(BoxFeature(40.0, 20.0, 10.0))
history.add(SelectedFilletFeature(0, SelectEdges(Edges.parallelTo(Direction3.Z), expected = 4), 2.0))
history.add(HoleFeature(1, 8.0, 20.0, 20.0, 10.0, 15.0))
history.undo() // the hole is gone again
history.redo() // ... and back
history.update(2) { (it as HoleFeature).copy(diameter = 10.0) }
// The history holds no shapes; they exist only for the lifetime of the rebuild result.
val shape = history.rebuild().final

stepFile(fileName = "feature-history-undo.step") {
    root(definition)
    shape(definition, shape)
}
