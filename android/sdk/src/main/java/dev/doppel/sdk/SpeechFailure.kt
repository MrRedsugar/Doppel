package dev.doppel.sdk

import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** User-facing errors are selected locally; provider bodies and credentials never become UI text. */
internal enum class SpeechFailure(val message: String) {
    MICROPHONE_STARTING("麦克风还没准备好，请等到“正在听”后说话"),
    MICROPHONE_MUTED("麦克风已被系统关闭，请开启麦克风访问后重试"),
    MICROPHONE_SILENCED("当前录音被系统静音，请结束其他录音或通话后重试"),
    NO_AUDIO("没有收到麦克风声音，请检查耳机连接或麦克风后重试"),
    TOO_SHORT("录音太短，请按住说完后再松手"),
    SILENT("录音中没有收到声音，请靠近麦克风或检查耳机后重试"),
    AUTH("语音服务凭据无效或未配置，请检查模型连接中的 API Key"),
    QUOTA("语音服务账户额度不足，请检查账户余额"),
    BUSY("语音服务暂时繁忙，请稍后重新按住说话"),
    EMPTY_RESULT("服务没有返回识别文字，请重新说一遍或输入文字"),
    TIMEOUT("语音识别超时，请检查网络后重试"),
    NETWORK("语音服务连接失败，请检查手机网络后重试"),
    GATEWAY("语音网关无法连接，请连接网关或启用手机直连"),
    UNAVAILABLE("语音服务尚未配置，请检查模型连接"),
    INVALID_AUDIO("录音格式异常，请重新录音"),
    UNKNOWN("语音识别未完成，请重新说一遍或输入文字");

    companion object {
        fun forRecording(ready: Boolean, muted: Boolean, silenced: Boolean,
                         sampleCount: Int, durationMs: Int, hasSignal: Boolean): SpeechFailure? = when {
            muted -> MICROPHONE_MUTED
            silenced -> MICROPHONE_SILENCED
            !ready -> MICROPHONE_STARTING
            sampleCount == 0 -> NO_AUDIO
            durationMs < 300 -> TOO_SHORT
            !hasSignal -> SILENT
            else -> null
        }

        fun from(error: Exception, direct: Boolean): SpeechFailure {
            val detail = error.message.orEmpty()
            return when {
                error is SocketTimeoutException || detail.contains("超时") -> TIMEOUT
                detail.contains("凭据") || detail.contains("API Key") || detail.contains("身份验证") ||
                    detail.contains("无权访问") || detail == "请先连接账户再使用语音" -> AUTH
                detail.contains("余额不足") || detail.contains("额度不足") -> QUOTA
                detail.contains("繁忙") || detail.contains("限流") || detail.contains("预览正在识别") -> BUSY
                detail.contains("结果为空") || detail == "Missing transcript" -> EMPTY_RESULT
                detail.contains("尚未配置") -> UNAVAILABLE
                detail.contains("录音格式") || detail.contains("PCM16") -> INVALID_AUDIO
                error is java.io.IOException || error is UnknownHostException || detail.contains("连接中断") ->
                    if (direct) NETWORK else GATEWAY
                else -> UNKNOWN
            }
        }
    }
}
