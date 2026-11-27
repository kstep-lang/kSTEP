package dev.kstep.desktop.mcp

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.run.DesktopError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/** A host whose snapshot the test sets; [onRead] runs on every read (to block a worker). */
class FakeHost(
    var snap: DesktopStateSnapshot = DesktopStateSnapshot.EMPTY,
    var onRead: () -> Unit = {},
) : McpHost {
    override val snapshot: DesktopStateSnapshot get() = onRead().let { snap }
    override val appVersion: String = "9.9.9-test"
}

fun snap(
    kind: EditorKind = EditorKind.SCRIPT,
    text: String? = "box(1, 1, 1)\n",
    path: Path? = Path.of("/tmp/part.kstep.kts"),
    dirty: Boolean = false,
    run: RunKind = RunKind.OK,
    origin: PreviewKind? = PreviewKind.SAVED_FILE,
    parts: Int? = 2,
    triangles: Int? = 24,
    draft: Boolean = false,
    knowledge: Boolean = false,
    hint: String? = null,
    revision: Long = 7L,
    runReport: McpRunReport? = null,
    scene: McpScene? = null,
    selection: SelectionState? = null,
    visibility: VisibilityState = VisibilityState(),
    settings: McpViewSettings = McpViewSettings(),
) = DesktopStateSnapshot(
    path,
    kind,
    text,
    dirty,
    run,
    origin,
    parts,
    triangles,
    draft,
    knowledge,
    hint,
    revision,
    runReport,
    scene,
    selection,
    visibility,
    settings,
)

/** A part with a unit box at [x] (mm), [triangles] triangles. */
fun partInfo(
    name: String,
    index: Int,
    triangles: Int = 12,
    sourceLine: Int? = index + 1,
    color: String = "#336699",
) = PartInfo(
    name = name,
    firstTriangle = index * triangles,
    triangleCount = triangles,
    placement = PartPlacement(doubleArrayOf(index.toDouble(), 0.0, 0.0), DoubleArray(9)),
    colorHex = color,
    bboxMin = doubleArrayOf(index.toDouble(), 0.0, 0.0),
    bboxMax = doubleArrayOf(index + 2.0, 3.0, 4.0),
    centroid = doubleArrayOf(index + 1.0, 1.5, 2.0),
    sourceLine = sourceLine,
)

fun partTable(vararg names: String): PartTable {
    val parts = names.mapIndexed { i, n -> partInfo(n, i) }
    val map = IntArray(parts.sumOf { it.triangleCount }) { t -> t / 12 }
    return PartTable(parts, map)
}

fun partTable(parts: List<PartInfo>): PartTable = PartTable(parts, IntArray(0))

/** A scene of the named parts without a tree. */
fun sceneOf(vararg names: String) = McpScene(partTable(*names), null, 3L)

/** A tree from `(name, parent, partOf)` triples in pre-order. */
fun treeOf(vararg nodes: Triple<String, Int, Int>) =
    AssemblyTree(
        Array(nodes.size) { nodes[it].first },
        IntArray(nodes.size) { nodes[it].second },
        IntArray(nodes.size) { nodes[it].third },
    )

fun errorList(n: Int) = List(n) { DesktopError("KSTEP-S-001", it + 1, 2, "problem $it") }

class RawResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    fun json(): JsonObject = Json.parseToJsonElement(body) as JsonObject
}

object Raw {
    /**
     * Sends [bytes] to 127.0.0.1:[port] and reads what comes back until the server closes. A connection that is closed
     * or reset before a status line gives status -1. The body of the request is written in a separate thread so a
     * server that answers early cannot block the test.
     */
    fun send(
        port: Int,
        bytes: ByteArray,
        keepOpenMs: Long = 0,
    ): RawResponse {
        Socket().use { socket ->
            socket.soTimeout = 8000
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            val writer =
                Thread {
                    try {
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()
                    } catch (_: Exception) {
                        // the server may have answered and closed already
                    }
                }.also { it.isDaemon = true }
            writer.start()
            val received =
                try {
                    socket.getInputStream().readAllBytes()
                } catch (_: Exception) {
                    ByteArray(0)
                }
            writer.join(2000)
            return parse(received)
        }
    }

    private fun parse(raw: ByteArray): RawResponse {
        val text = String(raw, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        if (end < 0 || !text.startsWith("HTTP/")) return RawResponse(-1, emptyMap(), "")
        val lines = text.substring(0, end).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = LinkedHashMap<String, MutableList<String>>()
        lines.drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i >
                0
            ) {
                headers.getOrPut(line.substring(0, i).lowercase()) { mutableListOf() } +=
                    line.substring(i + 1).trim()
            }
        }
        val body = String(raw, end + 4, raw.size - end - 4, Charsets.UTF_8)
        return RawResponse(status, headers, body)
    }

    /** One request with exactly the given header lines (no automatic ones) and body. */
    fun request(
        port: Int,
        method: String = "POST",
        path: String = "/mcp",
        headers: List<String>,
        body: ByteArray = ByteArray(0),
        declaredLength: Long? = null,
    ): RawResponse {
        val head =
            buildString {
                append("$method $path HTTP/1.1\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (declaredLength != null) {
                    append("Content-Length: $declaredLength\r\n")
                } else if (body.isNotEmpty()) {
                    append("Content-Length: ${body.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
        return send(port, head.toByteArray(Charsets.ISO_8859_1) + body)
    }

    /** A well-formed MCP POST; every part can be overridden. */
    fun mcp(
        port: Int,
        body: String,
        token: String?,
        hostHeader: String? = "127.0.0.1:$port",
        extra: List<String> = emptyList(),
        contentType: String? = "application/json",
        authorization: String? = token?.let { "Bearer $it" },
        path: String = "/mcp",
        method: String = "POST",
    ): RawResponse {
        val headers =
            buildList {
                hostHeader?.let { add("Host: $it") }
                authorization?.let { add("Authorization: $it") }
                contentType?.let { add("Content-Type: $it") }
                addAll(extra)
            }
        return request(port, method, path, headers, body.toByteArray(Charsets.UTF_8))
    }
}

fun <T> withDir(block: (Path) -> T): T {
    val dir = TestSupport.newDir()
    try {
        return block(Files.createDirectory(dir.resolve("config")))
    } finally {
        dir.toFile().deleteRecursively()
    }
}

fun rpc(
    method: String,
    id: Int = 1,
    params: String? = null,
) = """{"jsonrpc":"2.0","id":$id,"method":"$method"${params?.let { ""","params":$it""" } ?: ""}}"""
