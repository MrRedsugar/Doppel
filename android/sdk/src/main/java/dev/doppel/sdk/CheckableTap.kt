package dev.doppel.sdk

internal object CheckableTap {
    enum class Decision { CLICK, ALREADY_SET, REQUIRE_STATE, NOT_CHECKABLE }

    fun decide(checkable: Boolean, checked: Boolean, desired: Boolean?): Decision = when {
        !checkable && desired != null -> Decision.NOT_CHECKABLE
        !checkable -> Decision.CLICK
        desired == null -> Decision.REQUIRE_STATE
        checked == desired -> Decision.ALREADY_SET
        else -> Decision.CLICK
    }
}
