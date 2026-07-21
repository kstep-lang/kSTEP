// hello-invalid.kstep.kts -- demonstrates the aggregating root(ValidationResult) form
// (kSTEP-ADR-0001 acceptance criterion #3): an empty product id violates product's WHERE rule
// (SELF.id <> ''), collected into the resulting KStepModel.violations as a structured
// KSTEP-W-001 DslViolation instead of aborting the script.
//
// Run: kstep export --output json hello-invalid.kstep.kts
// -> {"status":"error","errorKind":"validation_failed","violations":[{"code":"KSTEP-W-001", ...}]}

stepFile(fileName = "hello-invalid.step") {
    root(product(id = "") { name = "Nameless" })
}
