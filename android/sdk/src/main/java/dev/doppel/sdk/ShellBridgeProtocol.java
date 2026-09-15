package dev.doppel.sdk;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Closed host-to-shell protocol. No command text, paths, key codes or input values from callers. */
final class ShellBridgeProtocol {
    static final int VERSION = 1;
    static final int MAX_REQUEST = 8192;
    static final int MAX_RESPONSE = 16 * 1024 * 1024;
    static final class Request {
        final String id, op;
        final boolean mutation, gesture;
        final long expiresAt;
        final JSONObject source;
        final List<String> argv;
        Request(String id, String op, boolean mutation, boolean gesture, JSONObject source, List<String> argv, long expiresAt) {
            this.id=id; this.op=op; this.mutation=mutation; this.gesture=gesture; this.source=source; this.argv=argv; this.expiresAt=expiresAt;
        }
    }
    static Request validate(JSONObject value, long now) {
        require(value != null, "missing_request");
        keys(value, "version", "id", "run_id", "op", "token", "expires_at", "source", "args");
        require(integer(value, "version", 1, 1) == VERSION, "version");
        String id = string(value, "id", 128), op = string(value, "op", 32);
        require(value.opt("token") instanceof String && value.optString("token").matches("[a-f0-9]{64}"), "token");
        long deadline = whole(value.opt("expires_at"));
        require(deadline >= now && deadline - now <= 5000, "expired_request");
        Set<String> reads = new HashSet<>(Arrays.asList("ping", "screenshot", "shutdown"));
        Map<String,String> keycodes = new HashMap<>();
        keycodes.put("back", "4"); keycodes.put("home", "3"); keycodes.put("recents", "187"); keycodes.put("menu", "82");
        boolean gesture = Arrays.asList("tap", "long_press", "swipe").contains(op);
        require(reads.contains(op) || keycodes.containsKey(op) || gesture, "unsupported_operation");
        boolean mutation = !reads.contains(op);
        JSONObject args = value.optJSONObject("args");
        require(args != null, "missing_arguments");
        JSONObject source = value.optJSONObject("source");
        if (mutation) {
            string(value, "run_id", 128);
            require(source != null, "missing_source");
            keys(source, "screen_id", "package_name", "captured_at", "width", "height", "rotation", "capture_id", "pixel_verification");
            string(source, "screen_id", 128); string(source, "package_name", 255);
            long capturedAt = whole(source.opt("captured_at"));
            require(capturedAt >= 0 && capturedAt <= now && now - capturedAt <= 15000, "stale_source");
            if(source.has("pixel_verification")) require(gesture && source.optString("pixel_verification").equals("host_target_rgb_edges") && now-capturedAt<=1000,"invalid_host_verification");
            integer(source, "width", 1, 16384); integer(source, "height", 1, 16384); integer(source, "rotation", 0, 3);
        }
        List<String> argv = new ArrayList<>();
        if (keycodes.containsKey(op)) {
            keys(args); argv.addAll(Arrays.asList("/system/bin/input", "keyevent", keycodes.get(op)));
        } else if (gesture) {
            string(source, "capture_id", 128);
            keys(args, "x", "y", "end_x", "end_y", "duration_ms");
            int width = source.optInt("width"), height = source.optInt("height");
            int x = integer(args, "x", 0, width-1), y = integer(args, "y", 0, height-1);
            if (op.equals("tap")) {
                require(args.length()==2, "tap_fields");
                argv.addAll(Arrays.asList("/system/bin/input", "touchscreen", "tap", ""+x, ""+y));
            } else {
                int endX=x, endY=y;
                if (op.equals("swipe")) { endX=integer(args,"end_x",0,width-1); endY=integer(args,"end_y",0,height-1); }
                else require(!args.has("end_x") && !args.has("end_y"), "long_press_fields");
                int duration=integer(args,"duration_ms",op.equals("long_press") ? 500 : 50,2000);
                argv.addAll(Arrays.asList("/system/bin/input", "touchscreen", "swipe", ""+x, ""+y, ""+endX, ""+endY, ""+duration));
            }
        } else keys(args);
        return new Request(id, op, mutation, gesture, source, Collections.unmodifiableList(argv), deadline);
    }
    static boolean authenticated(String expected, String supplied) {
        return expected != null && supplied != null && expected.matches("[a-f0-9]{64}") && supplied.matches("[a-f0-9]{64}") &&
            MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private static void keys(JSONObject value, String... names) {
        Set<String> allowed=new HashSet<>(Arrays.asList(names));
        for (Iterator<String> it=value.keys();it.hasNext();) require(allowed.contains(it.next()),"unknown_field");
    }
    private static String string(JSONObject value,String name,int limit) {
        Object found=value.opt(name); require(found instanceof String,"invalid_"+name);
        String result=(String)found; require(!result.trim().isEmpty() && result.length()<=limit,"invalid_"+name); return result;
    }
    private static long whole(Object value) { require(value instanceof Integer || value instanceof Long,"integer_required"); return ((Number)value).longValue(); }
    private static int integer(JSONObject value,String name,int low,int high) { long result=whole(value.opt(name)); require(result>=low && result<=high,"invalid_"+name); return (int)result; }
    private static void require(boolean value,String code) { if(!value) throw new IllegalArgumentException(code); }
    /** Never evict an uncertain command: a full helper must be explicitly restarted. */
    static final class Ledger {
        private final int capacity; private final Set<String> consumed=new HashSet<>();
        Ledger(int capacity) { this.capacity=capacity; }
        synchronized boolean claim(String id) { return consumed.size()<capacity && consumed.add(id); }
    }
}
