package dev.kstep.desktop.ai

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.ai.repair.AiLimits
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorText
import dev.kstep.desktop.run.AiCandidateCompiler
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerEnvironment
import dev.kstep.desktop.run.WorkerSandbox
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class AiWiringTest :
    StringSpec({
        "error codes D-001 to D-038 are gap-free and unique" {
            val desktop =
                DesktopErrorCodes::class.java.declaredFields
                    .filter {
                        it.type == String::class.java &&
                            java.lang.reflect.Modifier
                                .isStatic(it.modifiers)
                    }.map { it.apply { isAccessible = true }.get(null) as String }
            val all = desktop + AiErrorCodes.all
            all.toSet().size shouldBe all.size
            all.sorted() shouldBe (1..38).map { "KSTEP-D-%03d".format(it) }
        }

        "the AI script limit equals the editor limit" {
            AiLimits(maxScriptBytes = EditorText.MAX_BYTES).maxScriptBytes shouldBe 1_048_576
        }

        "the worker class path never carries Koog, an HTTP client or the AI module" {
            val cp = System.getProperty("kstep.desktop.workerClasspath").orEmpty().split(java.io.File.pathSeparator)
            cp.isNotEmpty() shouldBe true
            cp
                .filter { e ->
                    val n = e.substringAfterLast('/').substringAfterLast('\\')
                    listOf(
                        "prompt-executor",
                        "koog",
                        "ktor-client",
                        "kstep-desktop-ai",
                        "http-client-core",
                    ).any { n.contains(it) }
                }.shouldBeEmpty()
        }

        "secret-like variables are removed from the worker environment, others stay" {
            val env =
                mutableMapOf(
                    "KSTEP_AI_ANTHROPIC_KEY" to "x",
                    "ANTHROPIC_API_KEY" to "x",
                    "OPENAI_API_KEY" to "x",
                    "SOME_API_KEY" to "x",
                    "GITHUB_TOKEN" to "x",
                    "db_password" to "x",
                    "AWS_SECRET_ACCESS_KEY" to "x",
                    "PATH" to "/bin",
                    "HOME" to "/h",
                    "JAVA_HOME" to "/j",
                    "LANG" to "C",
                )
            WorkerEnvironment.scrub(env)
            env.keys.sorted() shouldBe listOf("HOME", "JAVA_HOME", "LANG", "PATH")
        }

        "a ProcessBuilder environment scrubbed this way no longer carries the key" {
            // a process that prints its environment; the parent sets nothing, so only inherited variables could appear
            val out = TestSupport.newDir()
            try {
                val pb = ProcessBuilder("env")
                pb.environment()["KSTEP_AI_ANTHROPIC_KEY"] = "leaky"
                WorkerEnvironment.scrub(pb.environment())
                val text =
                    pb
                        .start()
                        .inputStream
                        .readBytes()
                        .toString(Charsets.UTF_8)
                text shouldNotContain "leaky"
            } finally {
                out.toFile().deleteRecursively()
            }
        }

        "applyProposal is one undo step, makes the buffer dirty and writes nothing" {
            val dir = TestSupport.newDir()
            try {
                val file = dir.resolve("a.kstep.kts")
                Files.writeString(file, "old text")
                val s = EditorSession()
                s.open(file)
                TestSupport.await(30_000) { s.view.value.state is EditorState.Editing } shouldBe true
                s.applyProposal("new text\nmore")
                s.view.value.text shouldBe "new text\nmore"
                s.view.value.dirty shouldBe true
                Files.readString(file) shouldBe "old text"
                s.undo()
                s.view.value.text shouldBe "old text"
                s.close()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "AiCandidateCompiler: private file with the script suffix, removed afterwards, three results mapped" {
            val dir = TestSupport.newDir()
            try {
                var seen: java.nio.file.Path? = null
                var content: String? = null
                var results: RunResult = RunResult.Success(DesktopModel(null, 0, 1, Instant.EPOCH, 1, emptyList()))
                var failWith: ((java.nio.file.Path) -> RunResult)? = null
                val c =
                    AiCandidateCompiler(dir, { WorkerSandbox.MacSandboxExec("p", dir, dir) }) { file, _, _ ->
                        seen = file
                        content = Files.readString(file)
                        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
                        }
                        failWith?.invoke(file) ?: results
                    }
                c.compile("val a = 1") {}.shouldBeInstanceOf<CompileOutcome.Ok>()
                seen!!.fileName.toString().endsWith(".kstep.kts") shouldBe true
                seen!!.parent.fileName.toString() shouldBe "ai"
                content shouldBe "val a = 1"
                Files.exists(seen!!) shouldBe false

                results = RunResult.NativeUnavailable("no occt")
                c.compile("x") {}.shouldBeInstanceOf<CompileOutcome.Unverifiable>()

                failWith = { f -> RunResult.Failure(listOf(DesktopError("KSTEP-S-001", 3, 4, "bad $f"))) }
                val errs = c.compile("x") {}.shouldBeInstanceOf<CompileOutcome.Errors>()
                errs.diagnostics.single().message shouldNotContain dir.toString()
                errs.diagnostics.single().line shouldBe 3

                // removed even when the runner throws
                val boom =
                    AiCandidateCompiler(dir, { WorkerSandbox.MacSandboxExec("p", dir, dir) }) { f, _, _ ->
                        seen = f
                        error("boom")
                    }
                runCatching { boom.compile("x") {} }
                Files.exists(seen!!) shouldBe false
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
