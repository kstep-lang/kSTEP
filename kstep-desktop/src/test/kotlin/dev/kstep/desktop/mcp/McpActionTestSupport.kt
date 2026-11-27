package dev.kstep.desktop.mcp

import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

/** A port the test drives: it keeps a log of every call and holds the state the UI would hold. */
class FakePort(
    var kind: EditorKind = EditorKind.SCRIPT,
    var text: String? = "box(1, 1, 1)\n",
    var draftOffer: Boolean = false,
    var dirty: Boolean = false,
) : McpActionPort {
    val log = mutableListOf<String>()
    var gate = McpRunGate.ALLOWED
    var sandboxed = true
    var autoPreview = false
    var generation = 5L
    var outcome: McpRunOutcome? = McpRunOutcome.Finished
    var scene: McpActionScene? = null
    var selectionState = SelectionState()
    var visibilityState = VisibilityState()
    var show: McpShowTarget = McpShowTarget.NoFile
    var selectInEditorResult = true
    var beforeEdit: () -> Unit = {}
    var onEditor: () -> Unit = {}
    val notices = mutableListOf<AgentNoticeKind>()
    var lastRunMode: McpRunMode? = null
    var selectedRange: Pair<Int, Int>? = null

    fun sceneOf(vararg names: String) {
        val table = partTable(*names)
        scene = McpActionScene(table, null, VisibilityScene.of(table, null))
    }

    override fun editor(): McpEditorCapture {
        log += "editor"
        onEditor()
        return McpEditorCapture(kind, text, draftOffer, dirty)
    }

    override fun applyAgentEdit(
        expected: String,
        newText: String,
    ): AgentEditResult {
        log += "applyAgentEdit"
        beforeEdit()
        if (text != expected) return AgentEditResult.STALE
        if (text == newText) return AgentEditResult.UNCHANGED
        text = newText
        dirty = true
        return AgentEditResult.APPLIED
    }

    override fun runGate(mode: McpRunMode): McpRunGate {
        log += "runGate"
        return gate
    }

    override fun runIsSandboxed(): Boolean = sandboxed

    override fun autoPreviewOn(): Boolean = autoPreview

    override fun startRun(mode: McpRunMode): Long {
        log += "startRun"
        lastRunMode = mode
        return generation++
    }

    override fun awaitRun(
        afterGeneration: Long,
        timeoutMs: Long,
    ): McpRunOutcome? {
        log += "awaitRun"
        return outcome
    }

    override fun scene(): McpActionScene? {
        log += "scene"
        return scene
    }

    override fun selection(): SelectionState = selectionState

    override fun setSelection(next: SelectionState) {
        log += "setSelection"
        selectionState = next
    }

    override fun visibility(): VisibilityState = visibilityState

    override fun setVisibility(next: VisibilityState) {
        log += "setVisibility"
        visibilityState = next
    }

    override fun showInScriptTarget(part: String): McpShowTarget {
        log += "showInScriptTarget"
        return show
    }

    override fun selectInEditor(
        start: Int,
        end: Int,
        editorRevision: Int,
    ): Boolean {
        log += "selectInEditor"
        selectedRange = start to end
        return selectInEditorResult
    }

    override fun agentNotice(kind: AgentNoticeKind) {
        log += "agentNotice"
        notices += kind
    }
}

private const val UI_THREAD = "fake-ui"

/** The registry with a bridge to a fake UI thread. */
internal class ActionRig(
    val port: FakePort = FakePort(),
    allow: Boolean = false,
    timeoutMs: Long = 1000,
) {
    @Volatile var allow: Boolean = allow
    var now: Long = 1_000_000L
    val fakeHost = FakeHost(snap())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, UI_THREAD).apply { isDaemon = true } }
    val bridge =
        McpUiBridge(executor.asCoroutineDispatcher(), { Thread.currentThread().name == UI_THREAD }, timeoutMs)
    val actionHost = McpActionHost(bridge).also { it.port = port }
    val registry =
        McpToolRegistry(fakeHost, actions = McpActionContext(actionHost, { this.allow }, { now }))

    /** Calls a tool; time moves on by two seconds first, so neither the rate limit nor the run spacing interferes. */
    fun call(
        name: String,
        args: String = "{}",
        advance: Boolean = true,
    ): JsonObject {
        if (advance) now += 2000
        return registry.call(name, Json.parseToJsonElement(args).jsonObject, McpProtocol.STRUCTURED_VERSION)
    }

    fun close() = executor.shutdownNow()
}

val JsonObject.isError: Boolean get() = getValue("isError").jsonPrimitive.boolean

val JsonObject.body: JsonObject get() = getValue("structuredContent").jsonObject

val JsonObject.code: String get() = body.getValue("code").jsonPrimitive.content

internal fun <T> withRig(
    port: FakePort = FakePort(),
    allow: Boolean = true,
    block: (ActionRig) -> T,
): T {
    val rig = ActionRig(port, allow)
    try {
        return block(rig)
    } finally {
        rig.close()
    }
}

fun revisionOf(text: String): String = McpAgentText.revisionOf(text)

fun jsonStr(s: String): String =
    Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive
            .serializer(),
        kotlinx.serialization.json.JsonPrimitive(s),
    )
