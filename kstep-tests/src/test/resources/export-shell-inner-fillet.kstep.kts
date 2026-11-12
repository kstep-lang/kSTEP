// export-shell-inner-fillet.kstep.kts -- the four inner vertical edges of a shell, named after the shell's INPUT (ADR-0041).
// Box 40x30x20, top face opened, wall 2: the cavity is 36x26x18 (V = 7152). Rounding the four inner vertical edges
// (L = 18) with r = 1 adds (1 - pi/4) * r^2 * L each: 4 * 0.2146018 * 18 = 15.451332.
// Hand value: 7152 + 15.451332 = 7167.451, 15 faces (11 + 4 rounds), 1 solid.
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
    product("SHELL-NAME-1") {
        name = "Shell inner edges rounded by name"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-NAME-1-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-NAME-1-D") {
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
// The history holds no shapes; they exist only for the lifetime of the rebuild result.
val shape = history.rebuild().final

stepFile(fileName = "shell-inner-fillet.step") {
    root(definition)
    shape(definition, shape)
}
