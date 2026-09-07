package dev.doppel.developer
class MainActivity : dev.doppel.sdk.ClientActivity() {
    override val additionalSections = listOf("用量")
    override fun customPage(section: String) {
        if (section != "用量") return
        label("开发用量", 24f)
        sectionLabel("额度与计量")
        pointsPanel(developer = true)
        sectionLabel("连接")
        settingsRow("网关设置", "令牌与设备绑定", android.R.drawable.ic_menu_share) { selectSection("设置") }
    }
}
