package dev.doppel.sdk;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ShellBridgeProtocolTest {
    private JSONObject request(String op) throws Exception {
        return new JSONObject().put("version", 1).put("id", "command-1").put("run_id", "run-1")
            .put("op", op).put("token", "a".repeat(64)).put("expires_at", 5000)
            .put("source", new JSONObject().put("screen_id", "screen-1").put("captured_at", 1000)
                .put("package_name", "dev.notes").put("rotation", 0).put("width", 1440).put("height", 3200).put("capture_id", "capture-1"))
            .put("args", new JSONObject());
    }
    private void rejected(JSONObject value) {
        try { ShellBridgeProtocol.validate(value, 2000); fail("invalid command accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void fixedKeyNeverAcceptsArbitraryShell() throws Exception {
        assertEquals(Arrays.asList("/system/bin/input", "keyevent", "4"), ShellBridgeProtocol.validate(request("back"), 2000).argv);
        rejected(request("shell")); rejected(request("back").put("args", new JSONObject().put("command", "anything")));
    }
    @Test public void expiredOrFarFutureRequestIsRejected() throws Exception {
        rejected(request("back").put("expires_at", 1999)); rejected(request("back").put("expires_at", 9000));
    }
    @Test public void mutationNeedsFreshSourceAndRun() throws Exception {
        rejected(request("back").put("run_id", "")); rejected(request("back").put("source", JSONObject.NULL));
        JSONObject old = request("back"); old.getJSONObject("source").put("captured_at", -20000); rejected(old);
    }
    @Test public void tapRequiresBoundedIntegerCoordinatesAndCapture() throws Exception {
        JSONObject tap = request("tap").put("args", new JSONObject().put("x", 100).put("y", 200));
        assertEquals(Arrays.asList("/system/bin/input", "touchscreen", "tap", "100", "200"), ShellBridgeProtocol.validate(tap, 2000).argv);
        tap.getJSONObject("args").put("x", "100"); rejected(tap);
        tap.getJSONObject("args").put("x", 1440); rejected(tap);
        tap.getJSONObject("args").put("x", 1); tap.getJSONObject("source").remove("capture_id"); rejected(tap);
    }
    @Test public void swipeDurationAndEndpointsAreBounded() throws Exception {
        JSONObject swipe = request("swipe").put("args", new JSONObject().put("x", 10).put("y", 20).put("end_x", 50).put("end_y", 80).put("duration_ms", 300));
        assertEquals("300", ShellBridgeProtocol.validate(swipe, 2000).argv.get(7));
        swipe.getJSONObject("args").put("duration_ms", 9000); rejected(swipe);
    }
    @Test public void readOnlyProbeHasNoExecutionOrSourceRequirement() throws Exception {
        JSONObject ping = request("ping"); ping.remove("run_id"); ping.remove("source");
        assertFalse(ShellBridgeProtocol.validate(ping, 2000).mutation);
    }
    @Test public void actionIdsRemainConsumedAfterUncertainExecution() throws Exception {
        ShellBridgeProtocol.Ledger ledger = new ShellBridgeProtocol.Ledger(2);
        assertTrue(ledger.claim("a")); assertFalse(ledger.claim("a")); assertTrue(ledger.claim("b"));
        assertFalse(ledger.claim("c")); assertFalse(ledger.claim("a"));
    }
    @Test public void tokensRequireExactConstantTimeComparableBytes() throws Exception {
        assertTrue(ShellBridgeProtocol.authenticated("a".repeat(64), "a".repeat(64)));
        assertFalse(ShellBridgeProtocol.authenticated("a".repeat(64), "a".repeat(63)));
        assertFalse(ShellBridgeProtocol.authenticated("a".repeat(64), "b".repeat(64)));
    }
    @Test public void unconditionallyInjectedEnterAndTextAreUnavailable() throws Exception {
        for (String op : Arrays.asList("enter", "ime_done", "text", "keyevent", "send", "go")) rejected(request(op));
    }
    @Test public void hostAnchorVerificationMustBeExplicitAndFresh() throws Exception {
        JSONObject tap=request("tap").put("args",new JSONObject().put("x",100).put("y",200));
        tap.getJSONObject("source").put("pixel_verification","host_target_rgb_edges").put("captured_at",1500);
        assertTrue(ShellBridgeProtocol.validate(tap,2000).gesture);
        tap.getJSONObject("source").put("captured_at",999); rejected(tap);
        tap.getJSONObject("source").put("captured_at",1500).put("pixel_verification","model_said_safe"); rejected(tap);
    }
}
