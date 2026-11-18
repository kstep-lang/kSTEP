package dev.kstep.desktop.ai.arch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class AiArchitectureTest :
    FunSpec({
        val root = Path.of(System.getProperty("kstep.ai.sources"))
        val files =
            Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }.associate {
                root.relativize(it).toString().replace('\\', '/') to Files.readString(it)
            }

        fun offenders(
            pattern: Regex,
            allowed: (String) -> Boolean,
        ) = files.filter { (name, text) -> !allowed(name) && pattern.containsMatchIn(text) }.keys.toList()

        test("no Compose, no desktop ui, no script host in the AI module") {
            offenders(
                Regex(
                    "androidx\\.compose|org\\.jetbrains\\.compose|dev\\.kstep\\.desktop\\.ui|KStepScriptHost|KStepScript\\b|dev\\.kstep\\.script|dev\\.kstep\\.cli",
                ),
            ) {
                false
            } shouldBe emptyList()
        }
        test("ProcessBuilder only in SecretProcessRunner") {
            offenders(Regex("ProcessBuilder")) { it == "keys/SecretProcessRunner.kt" } shouldBe emptyList()
        }
        test("the JDK http client only in JdkHttpTransport") {
            offenders(Regex("java\\.net\\.http")) { it == "provider/HttpTransport.kt" } shouldBe emptyList()
        }
        test("reveal() only in provider and keys") {
            offenders(Regex("\\.reveal\\(\\)")) { it.startsWith("provider/") || it.startsWith("keys/") } shouldBe
                emptyList()
        }
        test("file writes only in SettingsStore") {
            offenders(Regex("Files\\.(write|writeString|newOutputStream|move|copy|createTempFile)")) {
                it == "settings/SettingsStore.kt"
            } shouldBe emptyList()
        }
        test("no log line interpolates prompt, reply, script, secret or key") {
            offenders(Regex("logger\\.\\w+ \\{[^}]*\\$\\{?(prompt|reply|script|secret|key)\\b")) { false } shouldBe
                emptyList()
        }
    })
