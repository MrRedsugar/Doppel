package dev.doppel.sdk;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded diagnostic facts only; never request data or process output. */
public final class ShellBridgeDiagnostic {
    private static final Set<String> SOURCES = values("helper", "client");
    private static final Set<String> OPERATIONS = values("ping", "screenshot", "shutdown", "back", "home", "recents", "menu", "tap", "long_press", "swipe");
    private static final Set<String> STAGES = values("process_start", "stdout_drain", "process_exit_wait", "process_complete", "request_write",
        "response_read", "response_id", "client_connect", "host_cancel");
    private static final Set<String> REASONS = values("process_started", "process_ok", "process_start_failed", "process_output_limit",
        "process_read_failed", "process_exit_timeout", "process_exit_nonzero", "process_deadline", "process_cancelled", "process_interrupted", "unexpected_control",
        "backend_response_failed", "backend_response_mismatch", "backend_write_failed", "host_cancelled_after_dispatch", "backend_connect_failed");
    private static final Pattern CLASS_NAME = Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_$]*\\.)*[A-Za-z_][A-Za-z0-9_$]*");

    private ShellBridgeDiagnostic() { }

    /** Invalid required vocabulary rejects the record; malformed optional facts are omitted without coercion. */
    public static JSONObject sanitize(JSONObject value) throws Exception {
        if (value == null) return null;
        String source = member(value, "source", SOURCES);
        String operation = member(value, "operation", OPERATIONS);
        String stage = member(value, "stage", STAGES);
        String reason = member(value, "reason_code", REASONS);
        if (source == null || operation == null || stage == null || reason == null) return null;
        JSONObject safe = new JSONObject().put("source", source).put("operation", operation).put("stage", stage).put("reason_code", reason);
        for (String key : new String[]{"process_started", "stdout_eof", "process_exited", "cancel_requested"}) {
            Object fact = value.opt(key);
            if (fact instanceof Boolean) safe.put(key, fact);
        }
        copyInteger(value, safe, "elapsed_ms", 0, 60000);
        copyInteger(value, safe, "timeout_ms", 0, 60000);
        copyInteger(value, safe, "stdout_bytes", 0, 16 * 1024 * 1024);
        if (Boolean.TRUE.equals(value.opt("process_exited"))) copyInteger(value, safe, "exit_code", -255, 255);
        Object exception = value.opt("exception_class");
        if (exception instanceof String && ((String) exception).length() <= 96 && CLASS_NAME.matcher((String) exception).matches())
            safe.put("exception_class", exception);
        return safe;
    }

    private static String member(JSONObject value, String key, Set<String> allowed) {
        Object raw = value.opt(key);
        return raw instanceof String && ((String) raw).length() <= 80 && allowed.contains(raw) ? (String) raw : null;
    }

    private static void copyInteger(JSONObject input, JSONObject output, String key, long minimum, long maximum) throws Exception {
        Object raw = input.opt(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) return;
        long value = ((Number) raw).longValue();
        if (value >= minimum && value <= maximum) output.put(key, value);
    }

    private static Set<String> values(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }
}
