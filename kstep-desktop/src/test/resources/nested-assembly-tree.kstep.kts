// nested-assembly-tree.kstep.kts -- a two-level assembly for the assembly tree (Welle D4, ADR-0054).
// Top uses the sub-assembly "Sub" twice (the second one moved by 100 mm in X) and the part "Y" once; "Sub" holds one part "X".
// Expected tree: Top > [Sub > [X], Sub > [X], Y], parts in pre-order X, X, Y.
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

val top = part("TOP", "Top")
val sub = part("SUB", "Sub")
val x = part("X", "X")
val y = part("Y", "Y")
val nauoSub1 = nextAssemblyUsageOccurrence("NAUO-S1") {
    name = "sub one"; relatingProductDefinition = top; relatedProductDefinition = sub
}.getOrThrow()
val nauoSub2 = nextAssemblyUsageOccurrence("NAUO-S2") {
    name = "sub two"; relatingProductDefinition = top; relatedProductDefinition = sub
}.getOrThrow()
val nauoY = nextAssemblyUsageOccurrence("NAUO-Y") {
    name = "y"; relatingProductDefinition = top; relatedProductDefinition = y
}.getOrThrow()
val nauoX = nextAssemblyUsageOccurrence("NAUO-X") {
    name = "x"; relatingProductDefinition = sub; relatedProductDefinition = x
}.getOrThrow()

stepFile(fileName = "nested-assembly-tree.step") {
    roots(nauoSub1, nauoSub2, nauoY, nauoX)
    shape(x, OcctKernel.makeBox(10.0, 10.0, 10.0))
    shape(y, OcctKernel.makeBox(20.0, 10.0, 10.0))
    place(nauoSub2, translation = vec(100.0, 0.0, 0.0))
    place(nauoY, translation = vec(0.0, 50.0, 0.0))
}
