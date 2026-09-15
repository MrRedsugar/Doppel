package dev.doppel.sdk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

class GuiGroundingSettingsActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);if(!DirectMode.isDeveloperBuild(this)){finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);UiTheme.init(this)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        UiTheme.bind(root){root.setBackgroundColor(UiTheme.background)};UiTheme.window(this,root);setContentView(root)
        val header=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(8),dp(20),dp(8))}
        header.addView(UiTheme.icon(this,UiIcons.back,"返回"){finish()},LinearLayout.LayoutParams(dp(44),dp(44)))
        header.addView(UiTheme.text(this,"视觉定位服务",18f,UiTheme.ink,true));root.addView(header)
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(32))}
        root.addView(ScrollView(this).apply{addView(content)})
        content.addView(UiTheme.text(this,"看清每一个目标",24f,UiTheme.ink,true))
        content.addView(UiTheme.text(this,"专用 GUI 模型辅助定位图标和画布。服务只返回位置，操作仍由手机核对并执行。",14f,UiTheme.muted).apply{setPadding(0,dp(12),0,dp(24))})
        val client=GuiGroundingClient(this)
        val address=UiTheme.field(this,"定位服务地址").apply {setText(client.endpoint());maxLines=1};content.addView(address)
        val token=UiTheme.field(this,"连接令牌（已保存时可留空）",secret=true).apply {
            isSaveEnabled=false;isSaveFromParentEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;maxLines=1
        };content.addView(token,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(12)})
        val status=UiTheme.text(this,if(client.enabled())"已启用" else "未启用",14f,UiTheme.muted);content.addView(status)
        fun save(enabled:Boolean){val value=token.text.toString().trim();token.setText("");status.text=runCatching {
            client.configure(address.text.toString(),value,enabled);if(enabled)"已启用 · 下次任务生效" else "已关闭"
        }.getOrElse{it.message?:"配置未保存"}}
        content.addView(UiTheme.command(this,"保存并启用",true){save(true)},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(16)})
        content.addView(UiTheme.command(this,"关闭辅助定位"){save(false)},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(12)})
        content.addView(UiTheme.text(this,"启用后，任务截图将发送到你配置的服务。令牌在手机内加密保存。开发时可通过 ADB 转发连接电脑上的本地模型；断开电脑后可关闭此功能，继续使用 MiMo 视觉。",14f,UiTheme.muted).apply{setPadding(0,dp(24),0,0)})
    }
    override fun onResume(){super.onResume();DirectMode.enterSettings(this);DeviceWorkerService.instance?.suspendLocally()}
    override fun onPause(){DirectMode.leaveSettings(this);super.onPause()}
    override fun onDestroy(){DirectMode.leaveSettings(this);super.onDestroy()}
    private fun dp(value:Int)=UiTheme.dp(this,value)
}
