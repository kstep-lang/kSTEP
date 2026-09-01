// hello-roots-plural-invalid.kstep.kts -- the roots(vararg) counterpart of hello-invalid.kstep.kts:
// a raw ValidationResult.Invalid passed through the plural roots(...) form must aggregate into
// KStepModel.violations, not get stored as a "root" (which used to make KStepModel.isValid true
// and later crash Part21Writer.write with an opaque "unsupported entity type" error). The
// product is otherwise valid (real frame_of_reference) so the sole violation stays KSTEP-W-001.

val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()

stepFile(fileName = "hello-roots-plural-invalid.step") {
    roots(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) })
}
