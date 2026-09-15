package dev.doppel.sdk;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Started by an already authorized ADB shell. It lives on-device after the ADB transport closes. */
public final class ShellBridgeMain {
    private final ShellBridgeProtocol.Ledger ledger = new ShellBridgeProtocol.Ledger(4096);
    private final ExecutorService readers = Executors.newCachedThreadPool();
    private String captureId, captureHash;
    private int captureWidth, captureHeight;
    private long captureAt;
    private boolean running = true;
    private DataInputStream activeInput;
    private String activeToken, activeCommand;
    private boolean cancelled;
    private JSONObject processDiagnostic;
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 2 || !args[0].matches("doppel\\.shell\\.[0-9]+") || !args[1].matches("[0-9]+"))
            throw new IllegalArgumentException("shell_uid_and_app_uid_required");
        int uid = Integer.parseInt(args[1]);
        if (uid < 10000 || !args[0].equals("doppel.shell." + uid)) throw new IllegalArgumentException("invalid_app_uid");
        new ShellBridgeMain().serve(args[0], uid);
    }
    private void serve(String name, int uid) throws Exception {
        try (LocalServerSocket server = new LocalServerSocket(name)) {
            while (running) {
                try (LocalSocket socket = server.accept()) {
                    if (socket.getPeerCredentials().getUid() != uid) continue;
                    socket.setSoTimeout(15000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    JSONObject hello = read(input);
                    String token = hello.optString("token");
                    if (!hello.optString("op").equals("hello") || hello.optInt("version") != 1 || !token.matches("[a-f0-9]{64}")) continue;
                    write(output, status().put("status", "ok").put("session", "authenticated"));
                    socket.setSoTimeout(0);
                    while (running) {
                        JSONObject request = read(input);
                        if (!ShellBridgeProtocol.authenticated(token, request.optString("token"))) break;
                        JSONObject response;
                        try {
                            activeInput=input; activeToken=token; activeCommand=request.optString("id"); cancelled=false; processDiagnostic=null;
                            response = execute(ShellBridgeProtocol.validate(request, SystemClock.elapsedRealtime()));
                        }
                        catch (IllegalArgumentException invalid) { response = result("blocked", "not_dispatched", invalid.getMessage()); }
                        catch (Exception unavailable) { response = withProcessDiagnostic(result("error", "unconfirmed", "backend_result_unconfirmed")); }
                        write(output, response.put("id", request.optString("id")));
                    }
                } catch (IOException disconnected) { /* A disconnect never replays an accepted request. */ }
                catch (Exception invalidClient) { /* Do not write request contents or secrets to a log. */ }
            }
        } finally { readers.shutdownNow(); }
    }
    private JSONObject execute(ShellBridgeProtocol.Request request) throws Exception {
        if (request.op.equals("ping")) return status().put("status", "ok").put("action_state", "not_dispatched");
        if (request.op.equals("shutdown")) { running=false; return result("ok", "not_dispatched", "disabled"); }
        if (request.op.equals("screenshot")) {
            byte[] png = capture();
            captureWidth = ByteBuffer.wrap(png, 16, 4).getInt(); captureHeight = ByteBuffer.wrap(png, 20, 4).getInt();
            captureId = UUID.randomUUID().toString(); captureHash = sha(png); captureAt = SystemClock.elapsedRealtime();
            return result("ok", "not_dispatched", "captured").put("image_base64", Base64.getEncoder().encodeToString(png))
                .put("mime_type", "image/png").put("capture_id", captureId).put("sha256", captureHash)
                .put("width", captureWidth).put("height", captureHeight).put("captured_at", captureAt);
        }
        if (!ledger.claim(request.id)) return result("blocked", "not_dispatched", "duplicate_or_ledger_full");
        if (request.gesture) {
            if (captureId == null || !captureId.equals(request.source.optString("capture_id")) ||
                request.source.optInt("width") != captureWidth || request.source.optInt("height") != captureHeight ||
                SystemClock.elapsedRealtime() - captureAt > 15000)
                return result("stale", "not_dispatched", "capture_mismatch");
            boolean verifiedByHost=request.source.optString("pixel_verification").equals("host_target_rgb_edges");
            if(verifiedByHost && (SystemClock.elapsedRealtime()-captureAt>1000 || request.source.optLong("captured_at")!=captureAt))
                return result("stale","not_dispatched","host_verification_expired");
            // Only the UID-authenticated host can attest its existing target/path RGB+edge check.
            // Without that fresh attestation retain full-image verification for standalone clients.
            if (!verifiedByHost && !captureHash.equals(sha(capture()))) return result("stale", "not_dispatched", "pixels_changed");
            captureId=null; captureHash=null;
        }
        checkCancellation();
        if (SystemClock.elapsedRealtime()>request.expiresAt) return result("stale", "not_dispatched", "request_expired_before_dispatch");
        try {
            process(request.op, request.argv, 100000, 4000);
            return withProcessDiagnostic(result("ok", "accepted", "shell_command_accepted").put("proves_business_success", false));
        } catch (Exception uncertain) {
            // input may already have injected the event. The host must pause instead of falling back.
            return withProcessDiagnostic(result("error", "unconfirmed", "shell_action_unconfirmed"));
        }
    }
    private JSONObject status() throws Exception {
        return new JSONObject().put("backend", "adb_shell_local_socket").put("uid", android.os.Process.myUid())
            .put("protocol", 1).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("capabilities", new JSONArray(Arrays.asList("screenshot", "back", "home", "recents", "menu", "tap", "long_press", "swipe")))
            .put("arbitrary_shell", false).put("ime_enter", false);
    }
    private static JSONObject result(String status, String actionState, String reason) throws Exception {
        return new JSONObject().put("status", status).put("action_state", actionState).put("reason_code", reason)
            .put("backend", "adb_shell_local_socket");
    }
    private byte[] capture() throws Exception {
        byte[] bytes = process("screenshot", Arrays.asList("/system/bin/screencap", "-p"), 10*1024*1024, 4000);
        if (bytes.length<24 || bytes[0]!=(byte)137 || bytes[1]!='P' || bytes[2]!='N' || bytes[3]!='G') throw new IOException("invalid_png");
        return bytes;
    }
    private JSONObject withProcessDiagnostic(JSONObject result) throws Exception {
        JSONObject safe=ShellBridgeDiagnostic.sanitize(processDiagnostic);
        if(safe!=null) result.put("shell_diagnostic",safe);
        return result;
    }
    private byte[] process(String operation, List<String> argv, int max, long timeout) throws Exception {
        long start=SystemClock.elapsedRealtime(), end=start+timeout;
        String stage="process_start", reason="process_start_failed";
        Process child=null;
        Future<byte[]> drained=null;
        java.util.concurrent.atomic.AtomicInteger stdoutBytes=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stdoutEof=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            checkCancellation();
            child=new ProcessBuilder(argv).redirectError(new File("/dev/null")).start();
            final Process started=child;
            stage="stdout_drain"; reason="process_read_failed";
            drained=readers.submit(() -> {
                try (InputStream input=started.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[16384]; int length;
                    while((length=input.read(buffer))!=-1) {
                        if(bytes.size()+length>max) throw new IOException("result_limit");
                        bytes.write(buffer,0,length); stdoutBytes.set(bytes.size());
                    }
                    stdoutEof.set(true);
                    return bytes.toByteArray();
                }
            });
            byte[] bytes;
            while(true) {
                checkCancellation();
                try { bytes=drained.get(Math.min(50, Math.max(1,end-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS); break; }
                catch(TimeoutException waiting) { if(SystemClock.elapsedRealtime()>=end) { reason="process_deadline"; throw waiting; } }
            }
            stage="process_exit_wait"; reason="process_exit_timeout";
            // EOF is not process completion. Use the original total deadline and keep cancellation live.
            // Android can also report false at a timed wait boundary just as the process exits.
            if(!ShellBridgeProcessWait.awaitExit(child,end,SystemClock::elapsedRealtime,this::checkCancellation))
                throw new TimeoutException("process_exit_timeout");
            reason="process_exit_nonzero";
            if(child.exitValue()!=0) throw new IOException("process_exit_nonzero");
            stage="process_complete"; reason="process_ok";
            processDiagnostic=processFacts(operation,stage,reason,start,timeout,child,stdoutBytes.get(),stdoutEof.get(),null);
            return bytes;
        } catch(Exception failure) {
            Throwable cause=failure instanceof ExecutionException && failure.getCause()!=null ? failure.getCause() : failure;
            if("request_cancelled".equals(cause.getMessage())) reason="process_cancelled";
            else if("unexpected_control".equals(cause.getMessage())) reason="unexpected_control";
            else if("result_limit".equals(cause.getMessage())) reason="process_output_limit";
            else if(cause instanceof InterruptedException) { reason="process_interrupted"; Thread.currentThread().interrupt(); }
            processDiagnostic=processFacts(operation,stage,reason,start,timeout,child,stdoutBytes.get(),stdoutEof.get(),cause);
            throw failure;
        } finally { if(child!=null) child.destroy(); if(drained!=null) drained.cancel(true); }
    }
    private JSONObject processFacts(String operation,String stage,String reason,long start,long timeout,Process child,int bytes,boolean eof,Throwable failure) throws Exception {
        JSONObject facts=new JSONObject().put("source","helper").put("operation",operation).put("stage",stage).put("reason_code",reason)
            .put("process_started",child!=null).put("stdout_eof",eof).put("stdout_bytes",bytes).put("process_exited",false)
            .put("cancel_requested",cancelled).put("elapsed_ms",Math.min(60000,Math.max(0,SystemClock.elapsedRealtime()-start)))
            .put("timeout_ms",timeout);
        if(child!=null) try { facts.put("exit_code",child.exitValue()).put("process_exited",true); } catch(IllegalThreadStateException running) { }
        if(failure!=null) facts.put("exception_class",failure.getClass().getName());
        return ShellBridgeDiagnostic.sanitize(facts);
    }
    private void checkCancellation() throws Exception {
        if(cancelled) throw new IOException("request_cancelled");
        if(activeInput!=null && activeInput.available()>0) {
            JSONObject control=read(activeInput);
            cancelled=control.optString("op").equals("cancel") && control.optString("id").equals(activeCommand) &&
                ShellBridgeProtocol.authenticated(activeToken,control.optString("token"));
            // The protocol is sequential. Any pipelined or invalid control must stop this command too.
            if(!cancelled) throw new IOException("unexpected_control");
            throw new IOException("request_cancelled");
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT,"%02x",value & 255));
        return out.toString();
    }
    private static JSONObject read(DataInputStream input) throws Exception {
        int length=input.readInt(); if(length<2 || length>ShellBridgeProtocol.MAX_REQUEST) throw new IOException("request_limit");
        byte[] bytes=new byte[length]; input.readFully(bytes); return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static void write(DataOutputStream output,JSONObject value) throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>ShellBridgeProtocol.MAX_RESPONSE) throw new IOException("response_limit");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
}
