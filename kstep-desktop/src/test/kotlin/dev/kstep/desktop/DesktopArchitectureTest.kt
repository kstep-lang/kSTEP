package dev.kstep.desktop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Structure rules of ADR-0051, checked on the sources: scripts are never evaluated in the desktop JVM. */
class DesktopArchitectureTest :
    StringSpec({
        val root =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin") }
                .first { Files.isDirectory(it) }

        fun sources(): List<Pair<Path, String>> =
            Files.walk(root).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.map { it to Files.readString(it) }.toList()
            }

        fun mcpSources() =
            sources().filter { (path, _) ->
                root
                    .relativize(path)
                    .toString()
                    .replace('\\', '/')
                    .startsWith("dev/kstep/desktop/mcp/")
            }

        "the mcp package is pure logic: no Compose, UI, AWT, Swing, process start, HTTP client or AI wire (ADR-0095)" {
            val forbidden =
                listOf(
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "dev.kstep.desktop.ui",
                    "java.awt",
                    "javax.swing",
                    "ProcessBuilder",
                    "Runtime.getRuntime().exec",
                    "java.net.http",
                    "dev.kstep.desktop.aiwire",
                )
            mcpSources()
                .flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "the mcp package imports nothing from the script module and nothing from the CLI (ADR-0095)" {
            mcpSources()
                .flatMap { (path, text) ->
                    text
                        .lineSequence()
                        .filter { it.startsWith("import dev.kstep.script.") || it.startsWith("import dev.kstep.cli.") }
                        .map { "${root.relativize(path)}: $it" }
                        .toList()
                }.shouldBeEmpty()
        }

        "the mcp package writes files only in the token store and the preferences store (ADR-0095)" {
            val writes =
                listOf(
                    "Files.write",
                    "Files.newOutputStream",
                    "Files.move",
                    "Files.createTempFile",
                    "Files.delete",
                    "FileOutputStream",
                    "PrivateConfigFile.writeAtomic",
                )
            mcpSources()
                .filter { (path, _) -> path.fileName.toString() !in setOf("McpTokenStore.kt", "McpPreferences.kt") }
                .flatMap { (path, text) ->
                    writes.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "the mcp package never compiles a pattern of its own, agent patterns use SafeRegex only (ADR-0096)" {
            val forbidden =
                listOf(
                    "Regex(",
                    ".toRegex(",
                    "java.util.regex",
                    " Pattern.compile(",
                    "(Pattern.compile(",
                    "kotlin.text.Regex",
                )
            // McpTokenStore checks the shape of its own token with a fixed pattern; nothing a caller sends reaches it.
            mcpSources()
                .filter { (path, _) -> path.fileName.toString() != "McpTokenStore.kt" }
                .flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "the mcp package neither runs nor evaluates a script and reads no file except in the two stores (ADR-0096)" {
            val noRun = listOf("LiveRunController", "WarmWorker", "WorkerRunner", "UserScriptSandbox")
            val noFiles = listOf("Files.", "FileInputStream", "Path.of(", "File(")
            mcpSources()
                .flatMap { (path, text) ->
                    val name = path.fileName.toString()
                    val run = noRun.filter { text.contains(it) }
                    val files =
                        if (name in setOf("McpTokenStore.kt", "McpPreferences.kt")) {
                            emptyList()
                        } else {
                            noFiles.filter { text.contains(it) }
                        }
                    (run + files).map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "the action port has no way to save, export, open, close or touch the clipboard (ADR-0097)" {
            val forbidden = listOf("save", "export", "open", "close", "clipboard")
            dev.kstep.desktop.mcp.McpActionPort::class.java.methods
                .map { it.name.lowercase() }
                .filter { name -> forbidden.any { name.contains(it) } }
                .shouldBeEmpty()
        }

        "the window side of the action port neither saves, exports, opens files nor uses the clipboard (ADR-0097)" {
            val text =
                sources()
                    .single { (path, _) ->
                        root.relativize(path).toString().replace('\\', '/') ==
                            "dev/kstep/desktop/ui/DesktopMcpActionPort.kt"
                    }.second
            val code =
                text.lineSequence().filter {
                    !it.trimStart().startsWith(
                        "*",
                    ) &&
                        !it.trimStart().startsWith("/*")
                }
            val banned = listOf(".save(", "saveAndWait", "export", "clipboard", "Files.", ".open(", "Clipboard")
            code
                .flatMap { line -> banned.filter { line.contains(it) }.map { "$it in: ${line.trim()}" } }
                .toList()
                .shouldBeEmpty()
        }

        "the file port has exactly the ADR-0098 methods and no parameter that could carry a path" {
            val expected =
                setOf(
                    "target",
                    "recentScripts",
                    "lineDelta",
                    "recentStillValid",
                    "beginSave",
                    "awaitSave",
                    "openRecent",
                    "planExport",
                    "startExport",
                    "awaitExport",
                )
            val methods = dev.kstep.desktop.mcp.McpFilePort::class.java.methods
            methods.map { it.name }.toSet() shouldBe expected
            val carriers = setOf(java.nio.file.Path::class.java, java.io.File::class.java, String::class.java)
            methods
                .flatMap { m -> m.parameterTypes.filter { it in carriers }.map { "${m.name}: ${it.simpleName}" } }
                .shouldBe(listOf("openRecent: String"))
        }

        "the window side of the file port never writes, copies, deletes, captures or uses the clipboard (ADR-0098)" {
            val text =
                sources()
                    .single { (path, _) ->
                        root.relativize(path).toString().replace('\\', '/') ==
                            "dev/kstep/desktop/ui/DesktopMcpFilePort.kt"
                    }.second
            val code =
                text.lineSequence().filter {
                    !it.trimStart().startsWith(
                        "*",
                    ) &&
                        !it.trimStart().startsWith("/*")
                }
            val banned =
                listOf(
                    "Files.write",
                    "newOutputStream",
                    "Files.move",
                    "Files.copy",
                    "Files.delete",
                    "clipboard",
                    "Clipboard",
                    "Robot",
                    "createScreenCapture",
                    "chooseScript",
                    "JFileChooser",
                )
            code
                .flatMap { line -> banned.filter { line.contains(it) }.map { "$it in: ${line.trim()}" } }
                .toList()
                .shouldBeEmpty()
        }

        "nothing in the desktop takes a screenshot of the screen or uses Dispatchers.Main" {
            sources()
                .flatMap { (path, text) ->
                    text
                        .lineSequence()
                        .filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }
                        .flatMap { line ->
                            listOf("java.awt.Robot", "createScreenCapture", "Dispatchers.Main")
                                .filter { line.contains(it) }
                                .map { "${root.relativize(path)} uses $it" }
                        }.toList()
                }.shouldBeEmpty()
        }

        "com.sun.net.httpserver is only used by the MCP HTTP server (ADR-0095)" {
            sources()
                .filter { (path, text) ->
                    text.contains("com.sun.net.httpserver") &&
                        root.relativize(path).toString().replace('\\', '/') !=
                        "dev/kstep/desktop/mcp/DesktopMcpHttpServer.kt"
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the desktop JVM never evaluates a script itself" {
            val forbidden = listOf("KStepScriptHost", "PreviewRenderer", "KStepScript", "runRender(")
            sources()
                .flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "only the live preview wiring uses the warm worker; AI, export and knowledge blocks stay cold (ADR-0081)" {
            val allowed =
                setOf(
                    "dev/kstep/desktop/run/LiveRunController.kt",
                    "dev/kstep/desktop/run/UserScriptSandbox.kt",
                    "dev/kstep/desktop/run/WorkerRunner.kt",
                )
            sources()
                .filter { (path, text) ->
                    val rel = root.relativize(path).toString().replace('\\', '/')
                    (text.contains("WarmWorkerClient") || text.contains("WarmWorkerSettings")) &&
                        rel !in allowed &&
                        !rel.startsWith("dev/kstep/desktop/run/WarmWorker")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the desktop imports from dev.kstep.script only the input path rules and their limits" {
            val allowed = setOf("dev.kstep.script.input.InputPathRules", "dev.kstep.script.ScriptInputLimits")
            sources()
                .flatMap { (path, text) ->
                    text
                        .lineSequence()
                        .filter { it.startsWith("import dev.kstep.script.") }
                        .map { it.removePrefix("import ").trim() }
                        .filter { it !in allowed }
                        .map { "${root.relativize(path)} imports $it" }
                        .toList()
                }.shouldBeEmpty()
        }

        "Compose is only used in the ui package" {
            sources()
                .filter { (path, _) ->
                    !root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .contains("/ui/")
                }.filter { (_, text) -> text.contains("androidx.compose") || text.contains("org.jetbrains.compose") }
                .map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the pick package is pure logic: no Compose and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/pick/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") || text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the settings package is pure logic: no Compose and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/settings/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") || text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the draft package is pure logic: no UI, editor, Compose, AWT, Swing, process or script host" {
            val forbidden =
                listOf(
                    "dev.kstep.desktop.ui",
                    "dev.kstep.desktop.edit",
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "java.awt",
                    "javax.swing",
                    "ProcessBuilder",
                    "KStepScriptHost",
                    "PreviewRenderer",
                    "java.net.http",
                )
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/draft/")
                }.flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "the menu package is pure logic: no Compose, no AWT, no Swing and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/menu/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("androidx.compose") ||
                        text.contains("java.awt") ||
                        text.contains("javax.swing")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the export package is pure logic: no Compose and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/export/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") || text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the convert package is pure logic: no Compose, no AWT, no Swing and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/convert/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("androidx.compose") ||
                        text.contains("java.awt") ||
                        text.contains("javax.swing")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "only the convert dialog host talks to the convert controller" {
            sources()
                .filter { (path, text) ->
                    !path.toString().replace('\\', '/').contains("/convert/") &&
                        !path.toString().endsWith("ConvertDialog.kt") &&
                        text.contains("ConvertController")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "worker processes are only started through WorkerEnvironment" {
            sources()
                .filter { (path, text) ->
                    !path.toString().endsWith("WorkerEnvironment.kt") &&
                        text.contains("ProcessBuilder")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the editor package is independent of the UI and of the watcher internals" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/edit/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("FileChangeWatcher") ||
                        text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "Koog and the AI module's internals are not used directly in the desktop sources" {
            sources()
                .filter { (_, text) -> text.contains("import ai.koog") || text.contains("java.net.http") }
                .map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "only AiController touches the key store and the settings file" {
            sources()
                .filter { (path, text) ->
                    !path.toString().endsWith("AiController.kt") &&
                        !path.toString().contains("/ui/") &&
                        (text.contains("KeychainBackend") || text.contains("SettingsStore") || text.contains("Secret."))
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the okf package is pure logic: no Compose, no UI, no process start, no script evaluation, no network" {
            val forbidden =
                listOf(
                    "dev.kstep.desktop.ui",
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "ProcessBuilder",
                    "KStepScriptHost",
                    "PreviewRenderer",
                    "java.net.http",
                )
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/okf/")
                }.flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }
        "the convert package is pure logic: no Compose, UI, AWT, process start, script evaluation or network" {
            val forbidden =
                listOf(
                    "dev.kstep.desktop.ui",
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "java.awt",
                    "javax.swing",
                    "ProcessBuilder",
                    "KStepScriptHost",
                    "PreviewRenderer",
                    "java.net.http",
                )
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/convert/")
                }.flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }
        "the recent and drop packages are pure logic: no Compose, no UI, no process start, no script evaluation" {
            sources()
                .filter { (path, _) ->
                    val rel = root.relativize(path).toString().replace('\\', '/')
                    rel.startsWith("dev/kstep/desktop/recent/") || rel.startsWith("dev/kstep/desktop/drop/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("androidx.compose") ||
                        text.contains("org.jetbrains.compose") ||
                        text.contains("ProcessBuilder")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }
    })
