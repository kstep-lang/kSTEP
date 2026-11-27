package dev.kstep.desktop.mcp

/** The link between the tools and the window: the bridge to the UI thread and the port the window provides. */
class McpActionHost(
    val bridge: McpUiBridge,
) {
    /** `null` while no window is composed (then every action answers `UI_BUSY`). */
    @Volatile
    var port: McpActionPort? = null
}

/** What the action tools need: the host, the live permission and a clock. */
internal class McpActionContext(
    val host: McpActionHost,
    val allowActions: () -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
) {
    val window = SlidingWindow()
    val runSpacing = RunSpacing()
    val waiter = SingleWaiter()
}
