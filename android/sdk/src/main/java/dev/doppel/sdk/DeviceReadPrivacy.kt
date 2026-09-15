package dev.doppel.sdk

/** Unknown one-time codes never become model context through notification or clipboard reads. */
internal object DeviceReadPrivacy {
    private val verification = Regex("验证码|校验码|动态码|一次性密码|一次性口令|\\b(?:otp|(?:verification|security|login)\\s+code|one[ -]?time\\s+(?:password|code))\\b", RegexOption.IGNORE_CASE)
    private val candidate = Regex("(?<![A-Za-z0-9])(?:[0-9]{4,8}|(?=[A-Za-z0-9]{4,10}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{4,10})(?![A-Za-z0-9])")
    private val genericNumericCode = Regex("\\bcode\\s*(?:is\\s*)?[:：=-]?\\s*[0-9]{4,8}(?![0-9])", RegexOption.IGNORE_CASE)
    private val formattedLetterCode = Regex("(?:验证码|校验码|动态码|(?i:verification code|security code|login code|otp))\\s*(?:为|是|[:：=])\\s*[A-Z]{4,10}(?![A-Za-z0-9])")
    fun codeNotification(title: String, body: String): Boolean {
        val text = "$title $body"
        return verification.containsMatchIn(text) && candidate.containsMatchIn(text) || genericNumericCode.containsMatchIn(text) || formattedLetterCode.containsMatchIn(text)
    }
    fun clipboardBlocked(text: String, sensitive: Boolean) = sensitive || LoginAssist.containsPrivateValue(text) || codeNotification("", text)
}
