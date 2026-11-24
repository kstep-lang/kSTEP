package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/** The controller on the real facade and a real folder: conflict, replace, and the content really replaced. */
class ConvertControllerIntegrationTest :
    StringSpec({
        "conflict, replace and the file content is replaced" {
            val dir = TestSupport.newDir()
            Files.writeString(dir.resolve("n.md"), "```kstep\nval a = 1\n```\n")
            val target = dir.resolve("n.kstep.kts")
            val request = ConvertRequest(dir, ConvertTarget.KTS, null, recursive = false)
            ConvertController().use { c ->
                c.begin(request)
                c.start()
                check(TestSupport.await(20_000) { c.state.value is ConvertState.Done })
                Files.readString(target) shouldBe "val a = 1\n"

                Files.writeString(target, "// user edit\n")
                c.back()
                c.start()
                check(TestSupport.await(20_000) { c.state.value is ConvertState.Conflict })
                c.state.value
                    .shouldBeInstanceOf<ConvertState.Conflict>()
                    .shown shouldBe listOf("n.kstep.kts")
                Files.readString(target) shouldBe "// user edit\n"

                c.replace()
                check(TestSupport.await(20_000) { c.state.value is ConvertState.Done })
                Files.readString(target) shouldBe "val a = 1\n"
            }
            dir.toFile().deleteRecursively()
        }
    })
