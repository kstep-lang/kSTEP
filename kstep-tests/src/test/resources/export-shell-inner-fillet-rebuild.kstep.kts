// export-shell-inner-fillet-rebuild.kstep.kts -- same names after a parameter change (ADR-0041).
// The box becomes 40x30x30; the selector Edges.parallelTo(Z) is evaluated on the new shell input, so the name still hits the
// four inner vertical edges (now L = 28). Hand value: 40*30*30 - 36*26*28 + 4 * (1 - pi/4) * 28 = 9792 + 24.035406
// = 9816.035, 15 faces.
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
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
    product("SHELL-NAME-2") {
        name = "Shell inner edges rounded by name after rebuild"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-NAME-2-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-NAME-2-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val history = FeatureHistory()
history.add(BoxFeature(40.0, 30.0, 20.0))
history.add(ShellFeature(0, SelectFaces(Faces.atMax(Direction3.Z), expected = 1), 2.0))
history.add(
    SelectedFilletFeature(
        1,
        GeneratedEdges(1, listOf(ShellInnerEdges(Edges.parallelTo(Direction3.Z))), GeneratedRole.CAVITY, expected = 4),
        1.0,
    ),
)
history.update(0) { (it as BoxFeature).copy(dz = 30.0) }
// The history holds no shapes; they exist only for the lifetime of the rebuild result.
val shape = history.rebuild().final

stepFile(fileName = "shell-inner-fillet-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
