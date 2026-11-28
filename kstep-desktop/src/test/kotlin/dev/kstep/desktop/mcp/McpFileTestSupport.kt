package dev.kstep.desktop.mcp

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Records what the gate scheduled and runs it on demand: no real time. */
class FakeConfirmScheduler : McpConfirmScheduler {
    val delays = CopyOnWriteArrayList<Long>()
    private val tasks = CopyOnWriteArrayList<Runnable>()

    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ): AutoCloseable {
        delays += delayMs
        tasks += task
        return AutoCloseable { tasks.remove(task) }
    }

    fun fire() {
        tasks.toList().forEach { it.run() }
    }
}

/** A file port the test drives; every call is logged. */
class FakeFilePort : McpFilePort {
    val log: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var kind = EditorKind.SCRIPT

    @Volatile var text = "box(1)\n"

    @Volatile var dirty = true

    @Volatile var fileId = "f1"

    @Volatile var draft = false

    @Volatile var workspace = false

    @Volatile var recents =
        listOf(McpRecentScript(0, "a.kstep.kts", "/home/u/p"), McpRecentScript(1, "b.kstep.kts", "/home/u/q"))

    @Volatile var delta: McpLineDelta? = McpLineDelta(2, 1)

    @Volatile var recentValid = true

    @Volatile var saveResult: McpSaveResult? = null

    @Volatile var openResult: McpOpenResult? = null

    @Volatile var plan: McpExportPlan = McpExportPlan.Ready("part.step", "/home/u/exports", Any())

    @Volatile var startResult: McpExportStart? = null

    @Volatile var exportResult: McpExportResult = McpExportResult.Done("part.step")

    fun stamp() = McpFileStamp(revisionOf(text), dirty, fileId)

    override fun target(): McpFileTarget {
        log += "target"
        return McpFileTarget(
            kind,
            if (kind == EditorKind.NONE) null else stamp(),
            "part.kstep.kts",
            "/home/u/p",
            draft,
            workspace,
        )
    }

    override fun recentScripts(): List<McpRecentScript> {
        log += "recentScripts"
        return recents
    }

    override fun lineDelta(stamp: McpFileStamp): McpLineDelta? {
        log += "lineDelta"
        return delta
    }

    override fun recentStillValid(index: Int): Boolean {
        log += "recentStillValid"
        return recentValid
    }

    override fun beginSave(stamp: McpFileStamp): Boolean {
        log += "beginSave"
        return stamp == stamp()
    }

    override fun awaitSave(
        stamp: McpFileStamp,
        timeoutMs: Long,
    ): McpSaveResult {
        log += "awaitSave"
        return saveResult ?: McpSaveResult.Saved(revisionOf(text))
    }

    override fun openRecent(
        index: Int,
        expectedName: String,
        stamp: McpFileStamp?,
    ): McpOpenResult {
        log += "openRecent"
        return openResult ?: McpOpenResult.Opened(expectedName)
    }

    override fun planExport(
        format: McpExportFormat,
        name: McpSafeFileName?,
        stamp: McpFileStamp,
    ): McpExportPlan {
        log += "planExport"
        return plan
    }

    override fun startExport(
        plan: McpExportPlan.Ready,
        format: McpExportFormat,
        stamp: McpFileStamp,
    ): McpExportStart {
        log += "startExport"
        return startResult ?: McpExportStart.Started(7, plan.fileName)
    }

    override fun awaitExport(
        start: McpExportStart.Started,
        timeoutMs: Long,
    ): McpExportResult {
        log += "awaitExport"
        return exportResult
    }
}

private const val FILE_UI_THREAD = "fake-file-ui"

/** The registry with a bridge to a fake UI thread, a real gate on a fake scheduler and a thread that plays the user. */
internal class FileRig(
    val port: FakeFilePort = FakeFilePort(),
    actions: Boolean = true,
    files: Boolean = true,
) {
    @Volatile var allowActions = actions

    @Volatile var allowFiles = files

    @Volatile var now: Long = 1_000_000L
    val scheduler = FakeConfirmScheduler()
    val gate = McpConfirmationGate(scheduler, { now })
    val seen = CopyOnWriteArrayList<McpConfirmRequest>()

    /** What the "user" does when a request appears; `null` leaves it open. Runs on the watcher thread. */
    @Volatile var user: ((McpConfirmRequest) -> Unit)? = null
    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, FILE_UI_THREAD).apply { isDaemon = true }
        }
    private val bridge =
        McpUiBridge(executor.asCoroutineDispatcher(), { Thread.currentThread().name == FILE_UI_THREAD }, 5000)
    val actionHost = McpActionHost(bridge, gate).also { it.filePort = port }
    val registry =
        McpToolRegistry(
            FakeHost(snap()),
            actions = McpActionContext(actionHost, { allowActions }, { now }, { allowFiles }),
        )

    @Volatile private var running = true
    private val watcher =
        Thread {
            var last = -1L
            while (running) {
                val request = gate.pending.value
                if (request != null && request.id != last) {
                    last = request.id
                    seen += request
                    user?.invoke(request)
                }
                Thread.sleep(2)
            }
        }.apply {
            isDaemon = true
            start()
        }

    fun allowAll() {
        user = { gate.resolve(it.id, McpConfirmDecision.ALLOW) }
    }

    fun denyAll() {
        user = { gate.resolve(it.id, McpConfirmDecision.DENY) }
    }

    fun call(
        name: String,
        args: String = "{}",
        advance: Boolean = true,
    ): JsonObject {
        if (advance) now += 20_000
        return registry.call(name, Json.parseToJsonElement(args).jsonObject, McpProtocol.STRUCTURED_VERSION)
    }

    fun close() {
        running = false
        executor.shutdownNow()
    }
}

internal fun <T> withFileRig(
    port: FakeFilePort = FakeFilePort(),
    actions: Boolean = true,
    files: Boolean = true,
    block: (FileRig) -> T,
): T {
    val rig = FileRig(port, actions, files)
    try {
        return block(rig)
    } finally {
        rig.close()
    }
}

/** True if the answer contains something that looks like a path. */
fun looksLikePath(answer: JsonObject): Boolean = Regex("""[/\\]|\b[A-Za-z]:[\\/]""").containsMatchIn(answer.toString())
