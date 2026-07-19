package dev.kstep.cli

// Deliberately plain println, not kotlin-logging (kSTEP M2 Welle 2 scope decision): this is a
// CLI's own stdout output -- the thing the user invoked the tool to see -- not diagnostic logging,
// even once this module gets real commands. See kstep-mcp for this project's actual kotlin-logging
// usage.
fun main(args: Array<String>) {
    println("kSTEP CLI — placeholder (M1 Welle 1). Args: ${args.joinToString(" ")}")
}
