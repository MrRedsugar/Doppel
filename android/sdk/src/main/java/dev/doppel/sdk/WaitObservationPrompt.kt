package dev.doppel.sdk

/** A decides readiness from fresh observations; this does not gate on global pixel stability. */
internal object WaitObservationPrompt {
    const val RULES = """
wait 必须输出 duration_ms、reason、evidence、wait_condition。evidence 是当前仍在自动处理的可见依据，wait_condition 是结束等待的可观察条件；定位不确定不是等待理由。入口已可操作就执行，背景动画或视频不等于页面未就绪。
对话等待继续、游戏显示暂停、确认框等待选择、键盘需要提交，通常需要操作才能推进，不能仅以这些画面作为等待依据。不熟悉操作时先阅读当前教学或说明，不急于跳过。相同画面多次出现时检查是否在等待输入，不机械重复或缩短 wait。确有下载、处理或符合计划的战斗在运行时可以继续等待，但必须以当前图重新判断。
等待本身不算进展。下载进度推进、目标状态切换等已观察到的新里程碑可记入state.completed_steps；不能把耗时增加、动画变化、重复理由或搜索返回本身写成完成步骤以延长等待。没有可确认进展时考虑换路线或请用户确认，不虚构进度。
wait_observation 提供次数、旧依据和耗时，reassess=true 时重新核对条件与可操作入口，旧理由不能代替本帧观察。当前截图不可用时说明技术原因，不虚构画面。只按 response_format 输出 decision 和同级 state，screen_context 不需要时填空字符串，不输出额外解释。
"""
}
