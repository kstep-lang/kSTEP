package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * Real geometry inside the sandbox (ADR-0059): the profile must not break rendering with OpenCascade. Named after OCCT
 * on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it. Skipped where no sandbox exists (Windows).
 */
class WorkerSandboxOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a placed two-part assembly renders inside the worker sandbox".config(
            enabled = available,
            timeout = 200.seconds,
        ) {
            val dir = TestSupport.newDir()
            try {
                val sandbox = WorkerSandbox.detect(dir, dir)
                if (sandbox is WorkerSandbox.Unavailable) {
                    val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
                    check(!(linux && System.getProperty("kstep.sandbox.require") == "true")) {
                        "the Linux sandbox is required but unavailable: ${sandbox.reason}"
                    }
                    return@config
                }
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                val result = WorkerRunner(dir, sandbox = sandbox).run(script, 1) { }
                val ok = result.shouldBeInstanceOf<RunResult.Success>()
                (ok.model.triangles > 0) shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
