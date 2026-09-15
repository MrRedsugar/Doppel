package dev.doppel.sdk

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Optional task IME. It performs the actual advertised editor action, never a generic Enter key. */
class ShellBridgeImeService : InputMethodService() {
    companion object {
        @Volatile private var active: ShellBridgeImeService? = null
        fun capability(): JSONObject = active?.snapshot() ?: JSONObject().put("available", false)
        /** Commit replaces the current selection; replace first selects the complete current field. */
        fun performTextAuthorized(packageName: String, editorId: String, text: String, replace: Boolean,
            isCurrent: () -> Boolean): JSONObject {
            if (!ShellBridgeEditorPolicy.textAllowed(text)) return rejected("invalid_input")
            val service = active ?: return rejected("ime_not_active")
            return onMain {
                val state=service.snapshot()
                if (!isCurrent() || !state.optBoolean("input_available") || state.optString("package_name") != packageName ||
                    state.optString("editor_id") != editorId) rejected("editor_changed")
                else {
                    val connection=service.currentInputConnection ?: return@onMain rejected("editor_unavailable")
                    if (replace && !connection.performContextMenuAction(android.R.id.selectAll)) return@onMain rejected("selection_not_available")
                    if (!isCurrent() || service.editorId != editorId) return@onMain rejected("editor_changed")
                    val accepted=connection.commitText(text,1)
                    JSONObject().put("status", if(accepted) "ok" else "error").put("action_state", if(accepted) "accepted" else "unconfirmed")
                        .put("reason_code", if(accepted) "editor_text_accepted" else "editor_text_unconfirmed")
                        .put("backend", "doppel_task_ime").put("proves_business_success",false)
                }
            }
        }
        fun performAuthorized(packageName: String, editorId: String, action: String, isCurrent: () -> Boolean): JSONObject {
            val service = active ?: return rejected("ime_not_active")
            return onMain {
                val current = service.snapshot()
                if (!isCurrent() || !current.optBoolean("available") || current.optString("package_name") != packageName ||
                    current.optString("editor_id") != editorId || current.optString("action") != action) rejected("editor_changed")
                else {
                    val accepted = service.currentInputConnection?.performEditorAction(if (action == "done") EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) == true
                    JSONObject().put("status", if (accepted) "ok" else "error").put("action_state", if (accepted) "accepted" else "unconfirmed")
                        .put("reason_code", if (accepted) "editor_action_accepted" else "editor_action_unconfirmed")
                        .put("backend", "doppel_task_ime").put("proves_business_success", false)
                }
            }
        }
        /** Execute A's explicit Enter intent using the focused editor's advertised action. */
        fun submitAuthorized(packageName: String, editorId: String, isCurrent: () -> Boolean): JSONObject {
            val service=active ?: return rejected("ime_not_active")
            return onMain {
                val state=service.snapshot()
                if(!isCurrent() || !state.optBoolean("input_available") || state.optString("package_name")!=packageName ||
                    state.optString("editor_id")!=editorId) return@onMain rejected("editor_changed")
                val editor=service.currentInputEditorInfo ?: return@onMain rejected("editor_unavailable")
                val connection=service.currentInputConnection ?: return@onMain rejected("editor_unavailable")
                val id=editor.imeOptions and EditorInfo.IME_MASK_ACTION
                val accepted=if(id in setOf(EditorInfo.IME_ACTION_DONE,EditorInfo.IME_ACTION_GO,EditorInfo.IME_ACTION_SEARCH,
                        EditorInfo.IME_ACTION_SEND,EditorInfo.IME_ACTION_NEXT,EditorInfo.IME_ACTION_PREVIOUS)) connection.performEditorAction(id)
                    else {
                        val down=connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
                        val up=connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))
                        down && up
                    }
                JSONObject().put("status",if(accepted) "ok" else "error").put("action_state",if(accepted) "accepted" else "unconfirmed")
                    .put("backend","doppel_task_ime").put("proves_business_success",false)
            }
        }
        private fun onMain(action: () -> JSONObject): JSONObject {
            val operation = FutureTask(action)
            return try {
                if (Looper.myLooper() == Looper.getMainLooper()) operation.run() else Handler(Looper.getMainLooper()).post(operation)
                operation.get(2000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { operation.cancel(false); JSONObject().put("status", "error").put("action_state", "unconfirmed").put("backend", "doppel_task_ime") }
        }
        private fun rejected(reason: String) = JSONObject().put("status", "blocked").put("action_state", "not_dispatched").put("reason_code", reason)
    }
    @Volatile private var editorId: String? = null
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting); editorId=UUID.randomUUID().toString(); active=this
    }
    override fun onFinishInput() { editorId=null; if (active === this) active=null; super.onFinishInput() }
    override fun onDestroy() { editorId=null; if (active === this) active=null; super.onDestroy() }
    override fun onCreateInputView(): android.view.View {
        UiTheme.init(this)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(UiTheme.dp(this@ShellBridgeImeService,16),UiTheme.dp(this@ShellBridgeImeService,10),UiTheme.dp(this@ShellBridgeImeService,16),UiTheme.dp(this@ShellBridgeImeService,10)) }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        root.addView(UiTheme.command(this,"任务输入法 · 切换键盘") {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        },LinearLayout.LayoutParams(-1,UiTheme.dp(this,48)))
        return root
    }
    private fun snapshot(): JSONObject {
        val editor = currentInputEditorInfo
        val allowed = editor != null && ShellBridgeEditorPolicy.inputAllowed(editor.inputType, editor.hintText?.toString(), editor.fieldName)
        val action = if (editor == null) "" else ShellBridgeEditorPolicy.action(editor.inputType, editor.imeOptions, editor.hintText?.toString(), editor.fieldName)
        val inputAvailable = editorId != null && currentInputConnection != null && allowed
        return JSONObject().put("available", inputAvailable && action.isNotBlank()).put("input_available", inputAvailable).apply {
            if (inputAvailable) put("package_name", editor!!.packageName).put("editor_id", editorId).put("action", action)
                .put("field_id", editor.fieldId)
        }
    }
}
