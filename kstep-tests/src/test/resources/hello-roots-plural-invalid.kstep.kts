// hello-roots-plural-invalid.kstep.kts -- the roots(vararg) counterpart of hello-invalid.kstep.kts:
// a raw ValidationResult.Invalid passed through the plural roots(...) form must aggregate into
// KStepModel.violations, not get stored as a "root" (which used to make KStepModel.isValid true
// and later crash Part21Writer.write with an opaque "unsupported entity type" error).

stepFile(fileName = "hello-roots-plural-invalid.step") {
    roots(product(id = "") { name = "Nameless" })
}
