package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21Writer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

private fun loadFixture(name: String): String =
    requireNotNull(
        KStepScriptExportTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

/**
 * End-to-end fixture test for kSTEP's `*.kstep.kts` scripting surface -- analogous to
 * [FreeCadValidationExportTest], but driving the whole `kstep export` pipeline (script text ->
 * [KStepScriptHost] -> [Part21Writer]) rather than calling the `kstep-core` builders directly
 * from Kotlin. Loads the fixture from test resources via `getResourceAsStream` (never an
 * absolute vault/repo path -- see this repo's CLAUDE.md CI-safety rule), matching every other
 * fixture-driven test in this module.
 */
class KStepScriptExportTest :
    StringSpec({
        "the hello-assembly.kstep.kts fixture evaluates, exports, and roundtrips through Part21Reader" {
            val source = loadFixture("hello-assembly.kstep.kts")
            val outcome = KStepScriptHost.eval(source, "hello-assembly.kstep.kts")
            val success = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.isValid shouldBe true
            success.model.roots shouldHaveSize 2

            val exported = Part21Writer.write(success.model.header, success.model.roots)

            // Automated half of kSTEP-ADR-0001's acceptance criterion #4: parse(export(model)) == model.
            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true

            // Manual half of acceptance criterion #2: write to disk for a real STEP-viewer (FreeCAD) import.
            val outDir = File("build/kstep-script-validation").apply { mkdirs() }
            File(outDir, "hello-assembly.step").writeText(exported)
        }

        "the hello-invalid.kstep.kts fixture surfaces its WHERE-rule violation as structured ValidationErrors" {
            val source = loadFixture("hello-invalid.kstep.kts")
            val outcome = KStepScriptHost.eval(source, "hello-invalid.kstep.kts")
            val validationErrors = outcome.shouldBeInstanceOf<KStepScriptOutcome.ValidationErrors>()
            validationErrors.violations shouldHaveSize 1
        }
    })
