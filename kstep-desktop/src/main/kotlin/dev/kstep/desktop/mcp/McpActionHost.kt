package dev.kstep.desktop.mcp

/** The link between the tools and the window: the bridge to the UI thread and the port the window provides. */
class McpActionHost(
    val bridge: McpUiBridge,
    /** The one open confirmation of a file action (ADR-0098). */
    val confirmations: McpConfirmationGate = McpConfirmationGate(ExecutorConfirmScheduler(), System::currentTimeMillis),
) {
    /** `null` while no window is composed (then every action answers `UI_BUSY`). */
    @Volatile
    var port: McpActionPort? = null

    /** The file actions of ADR-0098; `null` while no window is composed. */
    @Volatile
    var filePort: McpFilePort? = null
}

/** What the action tools need: the host, the live permission and a clock. */
internal class McpActionContext(
    val host: McpActionHost,
    val allowActions: () -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
    /** The third switch (ADR-0098): read live at every file call, never copied. */
    val allowFiles: () -> Boolean = { false },
) {
    val window = SlidingWindow()
    val runSpacing = RunSpacing()
    val waiter = SingleWaiter()
}
