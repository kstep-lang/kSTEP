package dev.kstep.tests

import java.io.File
import java.util.concurrent.TimeUnit

data class CliInvocationResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Subprocess-level `kstep-cli` invocation shared by [CliExportIntegrationTest] and
 * [CliRenderIntegrationTest] -- runs a genuine child `java dev.kstep.cli.MainKt` process rather
 * than calling `main()` in this test JVM, since `main()`'s error path calls `exitProcess`, which
 * would tear down the whole test JVM (see [CliMainTest]'s own KDoc for why that class only
 * exercises `resolveCommand`). Reuses *this* test JVM's own `java.class.path` system property as
 * the child's `-cp`, so no `installDist`/distribution build step is a precondition for these
 * tests -- every dependency `kstep export`/`kstep render` needs is already on this module's own
 * test runtime classpath.
 *
 * Extracted from `CliExportIntegrationTest` in kSTEP's headless-preview-rendering wave (see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc) so `CliRenderIntegrationTest` does not carry
 * a second, copy-pasted subprocess harness.
 */
class CliProcessRunner(
    private val workDir: File,
) {
    init {
        workDir.mkdirs()
    }

    fun run(
        vararg args: String,
        jvmArgs: List<String> = emptyList(),
        timeoutSeconds: Long = 120,
    ): CliInvocationResult {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val stdoutFile = File.createTempFile("kstep-cli-stdout", ".txt")
        val stderrFile = File.createTempFile("kstep-cli-stderr", ".txt")
        try {
            val command =
                buildList {
                    add(javaBin)
                    addAll(jvmArgs)
                    add("-cp")
                    add(classpath)
                    add("dev.kstep.cli.MainKt")
                    addAll(args)
                }
            val process =
                ProcessBuilder(command)
                    .directory(workDir)
                    .redirectOutput(stdoutFile)
                    .redirectError(stderrFile)
                    .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error("kstep-cli subprocess did not finish within ${timeoutSeconds}s (args=${args.toList()})")
            }
            return CliInvocationResult(
                exitCode = process.exitValue(),
                stdout = stdoutFile.readText(),
                stderr = stderrFile.readText(),
            )
        } finally {
            stdoutFile.delete()
            stderrFile.delete()
        }
    }
}
