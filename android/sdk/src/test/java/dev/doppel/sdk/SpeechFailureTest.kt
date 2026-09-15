package dev.doppel.sdk

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SpeechFailureTest {
    @Test fun releaseBeforeRecorderReadyDoesNotMasqueradeAsUnrecognizedSpeech() {
        assertEquals(SpeechFailure.MICROPHONE_STARTING, SpeechFailure.forRecording(false, false, false, 0, 0, false))
        assertEquals(SpeechFailure.NO_AUDIO, SpeechFailure.forRecording(true, false, false, 0, 0, false))
        assertEquals(SpeechFailure.TOO_SHORT, SpeechFailure.forRecording(true, false, false, 1600, 100, false))
        assertEquals(SpeechFailure.SILENT, SpeechFailure.forRecording(true, false, false, 16000, 1000, false))
        assertNull(SpeechFailure.forRecording(true, false, false, 16000, 1000, true))
    }

    @Test fun systemPrivacyAndCaptureContentionRemainDistinguishable() {
        assertEquals(SpeechFailure.MICROPHONE_MUTED, SpeechFailure.forRecording(true, true, false, 16000, 1000, false))
        assertEquals(SpeechFailure.MICROPHONE_SILENCED, SpeechFailure.forRecording(true, false, true, 16000, 1000, false))
    }

    @Test fun providerFailureRetainsUsefulCategoryWithoutCopyingRemoteText() {
        assertEquals(SpeechFailure.AUTH, SpeechFailure.from(IllegalStateException("MiMo 凭据无效或无权访问此模型"), true))
        assertEquals(SpeechFailure.QUOTA, SpeechFailure.from(IllegalStateException("MiMo 账户余额不足"), true))
        assertEquals(SpeechFailure.BUSY, SpeechFailure.from(IllegalStateException("MiMo 请求限流，请稍后手动继续"), true))
        assertEquals(SpeechFailure.TIMEOUT, SpeechFailure.from(SocketTimeoutException("private request details"), true))
        assertEquals(SpeechFailure.NETWORK, SpeechFailure.from(java.io.IOException("private request details"), true))
        assertEquals(SpeechFailure.GATEWAY, SpeechFailure.from(java.io.IOException("private request details"), false))
        val error = SpeechFailure.from(IllegalArgumentException("untrusted response with secret"), true)
        assertEquals(SpeechFailure.UNKNOWN, error)
        assertFalse(error.message.contains("secret"))
    }
}
