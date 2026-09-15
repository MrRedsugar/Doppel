package dev.doppel.sdk

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
    fun isDark(systemDark: Boolean): Boolean = this == DARK || this == SYSTEM && systemDark
    companion object { fun parse(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

data class ThemePalette(
    val background: Int, val surface: Int, val ink: Int, val muted: Int,
    val pale: Int, val line: Int, val green: Int, val danger: Int,
    val blue: Int, val spectrumBlue: Int, val spectrumCyan: Int, val spectrumPink: Int,
    val onPrimary: Int
) {
    fun values() = listOf(background, surface, ink, muted, pale, line, green, danger, blue, spectrumBlue, spectrumCyan, spectrumPink, onPrimary)
    fun colorAt(index: Int): Int = when (index) {
        0 -> background; 1 -> surface; 2 -> ink; 3 -> muted; 4 -> pale; 5 -> line; 6 -> green
        7 -> danger; 8 -> blue; 9 -> spectrumBlue; 10 -> spectrumCyan; 11 -> spectrumPink; 12 -> onPrimary
        else -> error("Unknown theme color")
    }
    fun blend(other: ThemePalette, progress: Float): ThemePalette {
        val colors = values().zip(other.values()).map { (from, to) -> blendColor(from, to, progress) }
        return ThemePalette(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors[6], colors[7], colors[8], colors[9], colors[10], colors[11], colors[12])
    }
    companion object {
        val light = ThemePalette(0xfffcfcfd.toInt(), 0xffffffff.toInt(), 0xff202123.toInt(), 0xff70747b.toInt(),
            0xffeff2f2.toInt(), 0xffe8eaed.toInt(), 0xff127968.toInt(), 0xffb24249.toInt(),
            0xff4370a4.toInt(), 0xff4b77eb.toInt(), 0xff4ec1cc.toInt(), 0xffe08bbf.toInt(), 0xffffffff.toInt())
        val dark = ThemePalette(0xff111214.toInt(), 0xff1d1f23.toInt(), 0xfff0f1f3.toInt(), 0xffa7adb7.toInt(),
            0xff292c31.toInt(), 0xff363a41.toInt(), 0xff80d4b9.toInt(), 0xfff39aa4.toInt(),
            0xff93b8e2.toInt(), 0xff91b2ff.toInt(), 0xff75d5db.toInt(), 0xffe9aad0.toInt(), 0xff191b1f.toInt())
        fun blendColor(from: Int, to: Int, progress: Float): Int {
            val fraction = progress.coerceIn(0f, 1f)
            var color = 0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val start = from ushr shift and 255
                val end = to ushr shift and 255
                color = color or ((start + (end - start) * fraction + 0.5f).toInt() shl shift)
            }
            return color
        }
    }
}
