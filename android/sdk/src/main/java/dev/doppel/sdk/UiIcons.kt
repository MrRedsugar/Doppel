package dev.doppel.sdk

/** Lucide vectors; license and pinned source are bundled in assets/third_party. */
object UiIcons {
    val menu = R.drawable.doppel_ic_menu
    val newChat = R.drawable.doppel_ic_square_pen
    val chevronDown = R.drawable.doppel_ic_chevron_down
    val arrowUp = R.drawable.doppel_ic_arrow_up
    val sparkles = R.drawable.doppel_ic_sparkles
    val panelLeft = R.drawable.doppel_ic_panel_left
    val microphone = R.drawable.doppel_ic_mic
    val lock = R.drawable.doppel_ic_lock_keyhole
    val pause = R.drawable.doppel_ic_pause
    val play = R.drawable.doppel_ic_play
    val back = R.drawable.doppel_ic_chevron_left
    val next = R.drawable.doppel_ic_chevron_right
    val plus = R.drawable.doppel_ic_plus
    val files = R.drawable.doppel_ic_files
    val close = R.drawable.doppel_ic_x
    val scan = R.drawable.doppel_ic_scan
    val delete = R.drawable.doppel_ic_trash
    val edit = R.drawable.doppel_ic_pencil
    val settings = R.drawable.doppel_ic_settings_2
    val device = R.drawable.doppel_ic_smartphone
    val account = R.drawable.doppel_ic_user_round
    val history = R.drawable.doppel_ic_clock_fading
    val connection = R.drawable.doppel_ic_plug
    val accessibility = R.drawable.doppel_ic_accessibility
    val refresh = R.drawable.doppel_ic_refresh_cw
    val mail = R.drawable.doppel_ic_mail
    val brand = R.drawable.doppel_ic_layers_2

    fun resolve(drawable: Int): Int = when (drawable) {
        android.R.drawable.ic_btn_speak_now -> microphone
        android.R.drawable.ic_lock_lock -> lock
        android.R.drawable.ic_media_pause -> pause
        android.R.drawable.ic_media_play -> play
        android.R.drawable.ic_media_previous -> back
        android.R.drawable.ic_media_next -> next
        android.R.drawable.ic_menu_add -> plus
        android.R.drawable.ic_menu_agenda -> files
        android.R.drawable.ic_menu_close_clear_cancel -> close
        android.R.drawable.ic_menu_crop -> scan
        android.R.drawable.ic_menu_delete -> delete
        android.R.drawable.ic_menu_edit -> edit
        android.R.drawable.ic_menu_manage -> settings
        android.R.drawable.ic_menu_mylocation -> device
        android.R.drawable.ic_menu_myplaces -> account
        android.R.drawable.ic_menu_recent_history -> history
        android.R.drawable.ic_menu_send -> arrowUp
        android.R.drawable.ic_menu_share -> connection
        android.R.drawable.ic_menu_view -> accessibility
        android.R.drawable.ic_popup_sync -> refresh
        android.R.drawable.ic_dialog_email -> mail
        else -> drawable
    }
}
