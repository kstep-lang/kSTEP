// hello-invalid.kstep.kts -- demonstrates the aggregating root(ValidationResult) form
// (kSTEP-ADR-0001 acceptance criterion #3): an empty product id violates product's WHERE rule
// (kstep_wr1: SELF.id <> ''), collected into the resulting KStepModel.violations as a structured
// KSTEP-W-001 DslViolation instead of aborting the script. Everything else about the product is
// deliberately valid (including a real frame_of_reference) so the one violation this fixture
// demonstrates is not obscured by an unrelated KSTEP-M-001 "you forgot the context" violation.
//
// Run: kstep export --output json hello-invalid.kstep.kts
// -> {"status":"error","errorKind":"validation_failed","violations":[{"code":"KSTEP-W-001", ...}]}

val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()

stepFile(fileName = "hello-invalid.step") {
    root(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) })
}
