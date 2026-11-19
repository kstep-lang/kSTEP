package dev.kstep.script

import java.text.Normalizer

/** The export formats a script may name in its `export { }` block (ADR-0063). [id] is the transport id. */
enum class ScriptExportFormat(
    val id: String,
    val extensions: List<String>,
) {
    STEP("step", listOf(".step", ".stp")),
    GLB("glb", listOf(".glb")),
    STL("stl", listOf(".stl")),
    THREE_MF("3mf", listOf(".3mf")),
}

/**
 * One file name a script declares for an export format (ADR-0063). A declaration only: the script never writes
 * anything, the desktop app suggests the name in its export dialog and the user still picks the folder.
 */
data class ScriptExportTarget(
    val format: ScriptExportFormat,
    val fileName: String,
)

/**
 * Strict validation of declared export names. A name is attacker-influenced text (scripts come from downloaded
 * projects), so it is rejected, never silently cleaned. The rules are mirrored by `ExportNaming.isValidDeclaredName`
 * in `kstep-desktop` (a parity test keeps both in step); `kstep-script` must not depend on the desktop module.
 */
object ScriptExportNames {
    const val MAX_TARGETS: Int = 4
    const val MAX_CODE_POINTS: Int = 120
    const val MAX_BYTES: Int = 255

    private val forbiddenChars = "/\\:*?\"<>|".toSet()
    private val bidiAndSeparators: Set<Int> =
        setOf(
            0x202A,
            0x202B,
            0x202C,
            0x202D,
            0x202E,
            0x2066,
            0x2067,
            0x2068,
            0x2069,
            0x200E,
            0x200F,
            0x061C,
            0x2028,
            0x2029,
        )
    private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    /** Returns [name] if it is a valid declared name for [format]; throws [IllegalArgumentException] with a fixed message. */
    fun validate(
        format: ScriptExportFormat,
        name: String,
    ): String {
        require(name.isNotEmpty()) { "export name must not be empty" }
        require(name.codePointCount(0, name.length) <= MAX_CODE_POINTS) {
            "export name is longer than $MAX_CODE_POINTS characters"
        }
        require(name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "export name is longer than $MAX_BYTES bytes" }
        require(Normalizer.isNormalized(name, Normalizer.Form.NFC)) { "export name must be NFC-normalized" }
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            require(!Character.isISOControl(cp) && cp !in bidiAndSeparators) {
                "export name contains a control or direction character"
            }
            require(!(cp < 0x10000 && cp.toChar() in forbiddenChars)) {
                "export name contains a character that is not allowed in file names"
            }
        }
        require(name != "." && name != "..") { "export name must be a file name" }
        require(!name.startsWith(".")) { "export name must not start with a dot" }
        require(!name.endsWith(".") && !name.endsWith(" ")) { "export name must not end with a dot or a space" }
        require(!name.startsWith(" ")) { "export name must not start with a space" }
        require(!reserved.matches(name)) { "export name is a reserved device name" }
        val lower = name.lowercase()
        require(format.extensions.any { lower.endsWith(it) && lower.length > it.length }) {
            "export name must end with ${format.extensions.joinToString(" or ")} for ${format.id}"
        }
        return name
    }
}

/** Receiver of `export { }`: declares at most one file name per format. Executes nothing. */
class ExportBlockBuilder internal constructor() {
    private val targets = mutableListOf<ScriptExportTarget>()

    fun step(fileName: String) = add(ScriptExportFormat.STEP, fileName)

    fun glb(fileName: String) = add(ScriptExportFormat.GLB, fileName)

    fun stl(fileName: String) = add(ScriptExportFormat.STL, fileName)

    fun threeMf(fileName: String) = add(ScriptExportFormat.THREE_MF, fileName)

    private fun add(
        format: ScriptExportFormat,
        fileName: String,
    ) {
        require(targets.size < ScriptExportNames.MAX_TARGETS) {
            "export { } declares at most ${ScriptExportNames.MAX_TARGETS} targets"
        }
        require(targets.none { it.format == format }) { "export { } declares the format ${format.id} twice" }
        targets += ScriptExportTarget(format, ScriptExportNames.validate(format, fileName))
    }

    internal fun build(): List<ScriptExportTarget> {
        require(targets.isNotEmpty()) { "export { } must declare at least one target" }
        return targets.toList()
    }
}
