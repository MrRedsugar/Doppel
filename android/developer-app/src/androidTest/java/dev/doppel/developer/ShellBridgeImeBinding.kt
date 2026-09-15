package dev.doppel.developer

/** Pure parser for the two actual-binding fields, never the configured default or arbitrary dump text. */
internal object ShellBridgeImeBinding {
    private val field = Regex("(?:^|\\s)(?:mCurMethodId|mCurId)\\s*=\\s*([^\\s]+)")
    private val component = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+")

    fun actualId(dump: String): String? {
        if (dump.length > 512 * 1024) return null
        val ids = field.findAll(dump).map { it.groupValues[1] }.toList()
        if (ids.isEmpty() || ids.any { it.length > 255 || !component.matches(it) }) return null
        fun expanded(id: String): String {
            val (pkg, name) = id.split('/', limit = 2)
            return "$pkg/" + if (name.startsWith('.')) pkg + name else name
        }
        if (ids.map(::expanded).distinct().size != 1) return null
        return ids.first()
    }
}
