package dev.doppel.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public final class ShellBridgeDiagnosticTest {
    private JSONObject diagnostic() throws Exception {
        return new JSONObject().put("source", "helper").put("operation", "tap")
            .put("stage", "process_exit_wait").put("reason_code", "process_exit_timeout");
    }

    @Test public void helperProcessFailureKeepsUsefulFactsWithoutClaimingExit() throws Exception {
        JSONObject original = diagnostic().put("process_started", true).put("stdout_eof", true)
            .put("process_exited", false).put("cancel_requested", false).put("elapsed_ms", 145L)
            .put("timeout_ms", 4000).put("stdout_bytes", 0).put("exception_class", "IOException").put("exit_code", 0);
        JSONObject safe = ShellBridgeDiagnostic.sanitize(original);
        assertNotNull(safe);
        assertEquals("process_exit_timeout", safe.getString("reason_code"));
        assertTrue(safe.getBoolean("process_started"));
        assertTrue(safe.getBoolean("stdout_eof"));
        assertFalse(safe.getBoolean("process_exited"));
        assertFalse(safe.has("exit_code"));
        assertEquals(145L, safe.getLong("elapsed_ms"));
        assertEquals(4000L, safe.getLong("timeout_ms"));
        assertEquals(0L, safe.getLong("stdout_bytes"));
        assertEquals("IOException", safe.getString("exception_class"));
    }

    @Test public void clientTransportFailureKeepsItsOwnStage() throws Exception {
        JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("source", "client").put("operation", "screenshot")
            .put("stage", "response_read").put("reason_code", "backend_response_failed").put("exception_class", "java.net.SocketTimeoutException"));
        assertNotNull(safe);
        assertEquals("client", safe.getString("source"));
        assertEquals("response_read", safe.getString("stage"));
        assertEquals("backend_response_failed", safe.getString("reason_code"));
    }

    @Test public void unsupportedRequiredVocabularyDropsTheWholeDiagnostic() throws Exception {
        String[][] rejected = {{"source", "model"}, {"operation", "shell"}, {"stage", "raw_stderr"},
            {"reason_code", "arbitrary-provider-message"}, {"source", "HELPER"}, {"operation", " tap "}};
        for (String[] entry : rejected) assertNull(entry[0], ShellBridgeDiagnostic.sanitize(diagnostic().put(entry[0], entry[1])));
    }

    @Test public void missingNullAndNonStringRequiredFieldsCannotProduceDiagnostic() throws Exception {
        assertNull(ShellBridgeDiagnostic.sanitize(null));
        for (String key : new String[]{"source", "operation", "stage", "reason_code"}) {
            JSONObject missing = diagnostic(); missing.remove(key);
            assertNull(key, ShellBridgeDiagnostic.sanitize(missing));
            for (Object value : new Object[]{JSONObject.NULL, "", 1, true, new JSONArray().put("helper")})
                assertNull(key, ShellBridgeDiagnostic.sanitize(diagnostic().put(key, value)));
        }
    }

    @Test public void rawContentAndUnrecognizedFieldsAreNeverForwarded() throws Exception {
        JSONObject input = diagnostic();
        for (String key : new String[]{"stderr", "stdout", "argv", "token", "message", "image_base64", "stack_trace", "source_text", "nested"})
            input.put(key, new JSONObject().put("private", "do-not-forward-private-content"));
        JSONObject safe = ShellBridgeDiagnostic.sanitize(input);
        assertNotNull(safe);
        assertEquals(4, safe.length());
        assertFalse(safe.toString().contains("do-not-forward-private-content"));
    }

    @Test public void optionalBooleansAreNeverCoercedFromTextOrNumbers() throws Exception {
        for (String key : new String[]{"process_started", "stdout_eof", "process_exited", "cancel_requested"}) {
            for (boolean value : new boolean[]{true, false}) {
                JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put(key, value));
                assertNotNull(safe); assertEquals(value, safe.getBoolean(key));
            }
            for (Object value : new Object[]{"true", "false", 1, 0, JSONObject.NULL}) {
                JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put(key, value));
                assertNotNull(safe); assertFalse(key, safe.has(key));
            }
        }
    }

    @Test public void integerAndLongLimitsRetainExactValues() throws Exception {
        String[] keys = {"elapsed_ms", "timeout_ms", "stdout_bytes"};
        int[] maxima = {60000, 60000, 16777216};
        for (int index = 0; index < keys.length; index++) {
            JSONObject zero = ShellBridgeDiagnostic.sanitize(diagnostic().put(keys[index], 0));
            JSONObject max = ShellBridgeDiagnostic.sanitize(diagnostic().put(keys[index], (long) maxima[index]));
            assertNotNull(zero); assertNotNull(max);
            assertEquals(0L, zero.getLong(keys[index])); assertEquals((long) maxima[index], max.getLong(keys[index]));
        }
    }

    @Test public void numericStringsFloatsAndOtherNumberTypesAreOmitted() throws Exception {
        for (String key : new String[]{"elapsed_ms", "timeout_ms", "stdout_bytes", "exit_code"}) {
            for (Object value : new Object[]{"1", 1.0, 1.0f, (short) 1, (byte) 1, new BigDecimal("1"), JSONObject.NULL}) {
                JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("process_exited", true).put(key, value));
                assertNotNull(safe); assertFalse(key + " / " + value.getClass().getSimpleName(), safe.has(key));
            }
        }
    }

    @Test public void outOfRangeAndOverflowingNumbersAreOmitted() throws Exception {
        String[] keys = {"elapsed_ms", "timeout_ms", "stdout_bytes", "exit_code"};
        long[] minima = {0, 0, 0, -255}; long[] maxima = {60000, 60000, 16777216, 255};
        for (int index = 0; index < keys.length; index++) {
            for (long value : new long[]{minima[index] - 1, maxima[index] + 1, Long.MIN_VALUE, Long.MAX_VALUE, 4294967297L}) {
                JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("process_exited", true).put(keys[index], value));
                assertNotNull(safe); assertFalse(keys[index], safe.has(keys[index]));
            }
        }
    }

    @Test public void exitCodeRequiresAnExplicitConfirmedProcessExit() throws Exception {
        for (Object state : new Object[]{false, "true", 1, JSONObject.NULL}) {
            JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("process_exited", state).put("exit_code", 12));
            assertNotNull(safe); assertFalse(safe.has("exit_code"));
        }
        JSONObject missing = ShellBridgeDiagnostic.sanitize(diagnostic().put("exit_code", 12));
        assertNotNull(missing); assertFalse(missing.has("exit_code"));
        for (long exit : new long[]{-255, 0, 255}) {
            JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("process_exited", true).put("exit_code", exit));
            assertNotNull(safe); assertEquals(exit, safe.getLong("exit_code"));
        }
    }

    @Test public void exceptionClassRejectsMessagesPathsAndUnboundedValues() throws Exception {
        for (String name : new String[]{"IOException", "java.net.SocketTimeoutException", "Outer$NestedException"}) {
            JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("exception_class", name));
            assertNotNull(safe); assertEquals(name, safe.getString("exception_class"));
        }
        for (String name : new String[]{"", "1Exception", "exception message", "Exception\nprivate", "java/lang/IOException", "java..IOException", "X".repeat(1000)}) {
            JSONObject safe = ShellBridgeDiagnostic.sanitize(diagnostic().put("exception_class", name));
            assertNotNull(safe); assertFalse(safe.has("exception_class"));
        }
    }

    @Test public void sanitizerDoesNotMutateInputOrReturnItsMutableObject() throws Exception {
        JSONObject input = diagnostic().put("message", "private");
        String before = input.toString();
        JSONObject safe = ShellBridgeDiagnostic.sanitize(input);
        assertNotNull(safe); assertNotSame(input, safe); assertEquals(before, input.toString());
        input.put("reason_code", "changed");
        assertEquals("process_exit_timeout", safe.getString("reason_code"));
    }
}
