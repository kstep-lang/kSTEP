# kSTEP script guide

A kSTEP script is a Kotlin script file (`*.kstep.kts`). It builds STEP (ISO 10303, AP242) product structure and,
optionally, solid geometry, and ends with a `stepFile(...) { ... }` expression, which is the model the tool shows.

Rules:
- No imports are needed: the DSL builders (`applicationContext`, `productContext`, `productDefinitionContext`, `product`,
  `productDefinitionFormation`, `productDefinition`, `nextAssemblyUsageOccurrence`, `stepFile`, `OcctKernel`, `Edges`,
  `Direction3`, ...) are already in scope.
- Every builder returns a `Result`; unwrap it with `.getOrThrow()`.
- The last expression of the script must be the `stepFile(fileName = "...") { ... }` block.
- Inside `stepFile`, `root(definition)` registers a product definition, `shape(definition, solid)` attaches geometry to it.
- Geometry comes from `OcctKernel` (`makeBox`, `fillet`, boolean operations); edges are selected by geometry
  (`Edges.parallelTo(Direction3.Z)`), not by index. Dimensions are in millimetres.
- Scripts have no includes and no relative imports. Keep everything in one file.

The following complete scripts compile and show the idiom.

<!-- example: hello-assembly.kstep.kts -->
```kotlin
// hello-assembly.kstep.kts -- exports a 3-part assembly (housing containing a bracket and a
// screw) to a STEP Part 21 file. No imports needed: applicationContext/productContext/
// productDefinitionContext/product/productDefinition/productDefinitionFormation/
// nextAssemblyUsageOccurrence/stepFile are all provided by the kSTEP script definition's
// defaultImports (dev.kstep.script.KStepScriptCompilationConfiguration).
//
// Run: kstep export hello-assembly.kstep.kts  ->  writes hello-assembly.step

// Shared context, built once and reused by every product/product_definition below: the real
// AP242 schema requires both of these (SET [1:?] OF product_context on product, a single
// product_definition_context on product_definition) -- kstep-core deliberately does not invent
// a placeholder instance behind the script's back, see docs/adr/ADR-0004.
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

val bracket =
    product("BRK-001") {
        name = "Bracket"
        description = "Mounting bracket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
val bracketDefinition =
    productDefinition("BRK-001-D") {
        formation = bracketFormation
        frameOfReference = defCtx
    }.getOrThrow()

val screw =
    product("SCR-001") {
        name = "Screw"
        description = "M4x10 fastener"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val screwFormation = productDefinitionFormation("SCR-001-F") { ofProduct = screw }.getOrThrow()
val screwDefinition =
    productDefinition("SCR-001-D") {
        formation = screwFormation
        frameOfReference = defCtx
    }.getOrThrow()

val housing =
    product("HSG-001") {
        name = "Housing"
        description = "Enclosure housing"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val housingFormation = productDefinitionFormation("HSG-001-F") { ofProduct = housing }.getOrThrow()
val housingDefinition =
    productDefinition("HSG-001-D") {
        formation = housingFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Last expression: the KStepModel the host exports.
stepFile(fileName = "hello-assembly.step") {
    root(
        nextAssemblyUsageOccurrence("NAUO-001") {
            name = "housing to bracket"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = bracketDefinition
            referenceDesignator = "RD-1"
        }.getOrThrow(),
    )
    root(
        nextAssemblyUsageOccurrence("NAUO-002") {
            name = "housing to screw"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = screwDefinition
            referenceDesignator = "RD-2"
        }.getOrThrow(),
    )
}
```
<!-- end example -->

<!-- example: export-box-fillet-z-edges.kstep.kts -->
```kotlin
// export-box-fillet-z-edges.kstep.kts -- 40x40x10 box, the four vertical edges rounded with r = 2 (ADR-0031).
// Expected: volume 16000 - 4*(1 - pi/4)*2^2*10 = 15965.664, 10 faces.
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
    product("BOXF-001") {
        name = "Box with rounded vertical edges"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOXF-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOXF-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Edges chosen by geometry, not by index: the four edges parallel to Z. `expected = 4` makes a
// wrong match an error instead of a silently different part.
val blank = OcctKernel.makeBox(40.0, 40.0, 10.0)
val shape = OcctKernel.fillet(blank, Edges.parallelTo(Direction3.Z), 2.0, expected = 4)

stepFile(fileName = "box-fillet.step") {
    root(definition)
    shape(definition, shape)
}
```
<!-- end example -->

<!-- example: export-colour-assembly-two-parts.kstep.kts -->
```kotlin
// export-colour-assembly-two-parts.kstep.kts -- a placed assembly of a red and a green box (Welle S, ADR-0039).
// Expected: two solids, volumes 6000 and 1000, bounding box [0,40] x [0,20] x [0,30]; the red part sits at the origin, the green one is moved by (30, 0, 0); colours survive the export.
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

val assembly = part("ASM", "Coloured assembly")
val red = part("RED", "Red part")
val green = part("GREEN", "Green part")
val nauoRed = nextAssemblyUsageOccurrence("NAUO-R") {
    name = "red"; relatingProductDefinition = assembly; relatedProductDefinition = red
}.getOrThrow()
val nauoGreen = nextAssemblyUsageOccurrence("NAUO-G") {
    name = "green"; relatingProductDefinition = assembly; relatedProductDefinition = green
}.getOrThrow()

stepFile(fileName = "colour-assembly-two-parts.step") {
    roots(nauoRed, nauoGreen)
    shape(red, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.0, 0.0), layer = "Red-Layer")
    shape(green, OcctKernel.makeBox(10.0, 10.0, 10.0), colour = StepColour.rgb(0.0, 0.6, 0.0))
    place(nauoGreen, translation = vec(30.0, 0.0, 0.0))
}
```
<!-- end example -->

<!-- example: export-boolean-cut.kstep.kts -->
```kotlin
// export-boolean-cut.kstep.kts -- Cut of two overlapping 20x20x10 boxes (Welle J, ADR-0029),
// exported with its product structure via `kstep export`. Expected: volume/faces 3000, 8 faces.
// The operands are intermediates left to the OcctShape Cleaner; only `shape` is registered.
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
    product("BOOL-CUT") {
        name = "Boolean cut"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOOL-CUT-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOOL-CUT-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val boxA = OcctKernel.makeBox(20.0, 20.0, 10.0)
val boxB = OcctKernel.translate(OcctKernel.makeBox(20.0, 20.0, 10.0), 10.0, 10.0, 0.0)
// Cut: box A minus the shifted copy = 4000 - 1000 = 3000 (an L-shaped prism).
val shape = OcctKernel.cut(boxA, boxB)

stepFile(fileName = "boolean-cut.step") {
    root(definition)
    shape(definition, shape)
}
```
<!-- end example -->

