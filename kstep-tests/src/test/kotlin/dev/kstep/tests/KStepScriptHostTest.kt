package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21Writer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf

private fun loadScriptFixture(name: String): String =
    requireNotNull(
        KStepScriptHostTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

/**
 * Unit tests for [KStepScriptHost.eval] -- every kind of `.kstep.kts` outcome the host is
 * responsible for turning into a structured [KStepScriptOutcome], never a thrown exception or a
 * raw stack trace. See [KStepScriptOutcome]'s own KDoc for what each case means and which
 * [KStepScriptOutcomeCodes] it maps to.
 */
class KStepScriptHostTest :
    StringSpec({
        "a valid 3-part-assembly script evaluates to Success and roundtrips through Part21Writer/Part21Reader" {
            val outcome =
                KStepScriptHost.eval(
                    loadScriptFixture("hello-assembly.kstep.kts"),
                    "hello-assembly.kstep.kts",
                )
            val success = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.isValid shouldBe true
            success.model.roots shouldHaveSize 2
            // The host must have defaulted the blank timestamp the fixture leaves unset.
            val timestamp = success.model.header.timestamp
            timestamp.shouldNotBeBlank()

            val exported = Part21Writer.write(success.model.header, success.model.roots)
            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true
        }

        "the plural roots(vararg) form correctly dispatches a mix of a raw entity and a ValidationResult.Valid" {
            val outcome =
                KStepScriptHost.eval(
                    loadScriptFixture("hello-roots-plural.kstep.kts"),
                    "hello-roots-plural.kstep.kts",
                )
            val success = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.isValid shouldBe true
            success.model.roots shouldHaveSize 2

            val exported = Part21Writer.write(success.model.header, success.model.roots)
            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true
        }

        "the plural roots(vararg) form aggregates a ValidationResult.Invalid instead of storing the wrapper as a root" {
            val outcome =
                KStepScriptHost.eval(
                    loadScriptFixture("hello-roots-plural-invalid.kstep.kts"),
                    "hello-roots-plural-invalid.kstep.kts",
                )
            val validationErrors = outcome.shouldBeInstanceOf<KStepScriptOutcome.ValidationErrors>()
            val violation = validationErrors.violations.shouldHaveSize(1).single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation.entityName shouldBe "product"
        }

        "the aggregating root(ValidationResult) form surfaces a WHERE-rule violation as ValidationErrors, not a crash" {
            val outcome =
                KStepScriptHost.eval(
                    loadScriptFixture("hello-invalid.kstep.kts"),
                    "hello-invalid.kstep.kts",
                )
            val validationErrors = outcome.shouldBeInstanceOf<KStepScriptOutcome.ValidationErrors>()
            val violation = validationErrors.violations.shouldHaveSize(1).single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation.entityName shouldBe "product"
        }

        "a missing mandatory reference surfaces as ValidationErrors with the structural KSTEP-M-001 code" {
            val script =
                """
                stepFile(fileName = "missing-ref.step") {
                    root(productDefinition("PD-001") { description = "no formation set" })
                }
                """.trimIndent()
            val outcome = KStepScriptHost.eval(script, "missing-ref.kstep.kts")
            val validationErrors = outcome.shouldBeInstanceOf<KStepScriptOutcome.ValidationErrors>()
            val violation = validationErrors.violations.shouldHaveSize(1).single()
            violation.code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
            violation.entityName shouldBe "product_definition"
        }

        "a getOrThrow() abort on an Invalid ValidationResult surfaces as ValidationErrors, not a RuntimeError" {
            val script = """product(id = "").getOrThrow()"""
            val outcome = KStepScriptHost.eval(script, "get-or-throw-abort.kstep.kts")
            val validationErrors = outcome.shouldBeInstanceOf<KStepScriptOutcome.ValidationErrors>()
            val violation = validationErrors.violations.shouldHaveSize(1).single()
            violation.code shouldBe KStepScriptOutcomeCodes.GET_OR_THROW_ABORT
            violation.message shouldContain "WHERE rule"
        }

        "a Kotlin syntax error surfaces as a structured CompilationError with a source location, not a crash" {
            val script = "val x = "
            val outcome = KStepScriptHost.eval(script, "syntax-error.kstep.kts")
            val compilationError = outcome.shouldBeInstanceOf<KStepScriptOutcome.CompilationError>()
            compilationError.diagnostics.isEmpty() shouldBe false
            val firstError = compilationError.diagnostics.first { it.severity == "ERROR" }
            firstError.line shouldBe 1
        }

        "an unresolved reference surfaces as a structured CompilationError" {
            val script = "totallyUndefinedFunctionCall()"
            val outcome = KStepScriptHost.eval(script, "unresolved-reference.kstep.kts")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.CompilationError>()
        }

        "a script whose last expression is Unit surfaces as NoModelProduced" {
            val script = "val x = 1"
            val outcome = KStepScriptHost.eval(script, "no-model.kstep.kts")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.NoModelProduced>()
        }

        "a script whose last expression is the wrong type surfaces as NoModelProduced" {
            val script = "42"
            val outcome = KStepScriptHost.eval(script, "wrong-type.kstep.kts")
            val noModel = outcome.shouldBeInstanceOf<KStepScriptOutcome.NoModelProduced>()
            noModel.message shouldContain "KStepModel"
        }

        "a script throwing a plain runtime exception surfaces as RuntimeError, not a crash" {
            val script = """error("boom")"""
            val outcome = KStepScriptHost.eval(script, "runtime-error.kstep.kts")
            val runtimeError = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            runtimeError.message shouldContain "boom"
        }

        "an empty script does not throw and resolves to a structured NoModelProduced outcome" {
            val outcome = KStepScriptHost.eval("", "empty.kstep.kts")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.NoModelProduced>()
        }

        "a whitespace-only script does not throw and resolves to a structured NoModelProduced outcome" {
            val outcome = KStepScriptHost.eval("   \n\t  \n", "whitespace.kstep.kts")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.NoModelProduced>()
        }
    })
