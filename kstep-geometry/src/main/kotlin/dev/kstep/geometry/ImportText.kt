package dev.kstep.geometry

/**
 * Removes control and format characters (C0, DEL, C1, bidi/line-separator formatting) from a name taken
 * from an imported file, so it cannot forge report lines or drive a terminal. Defence in depth: the
 * native layer already drops C0/C1 controls.
 */
fun sanitizeImportText(raw: String): String =
    buildString(raw.length) {
        for (ch in raw) {
            val type = Character.getType(ch)
            if (Character.isISOControl(ch) ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                continue
            }
            append(ch)
        }
    }
