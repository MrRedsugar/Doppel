package dev.doppel.sdk;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShellBridgeEditorPolicyTest {
    @Test public void ordinaryTextCanCommitEvenWithoutSubmitAction() throws Exception {
        assertTrue(ShellBridgeEditorPolicy.inputAllowed(1, "正文", "body"));
        assertEquals("", ShellBridgeEditorPolicy.action(1, 0, "正文", "body"));
    }
    @Test public void advertisedDoneAndNextAreTheOnlyAllowedActions() throws Exception {
        assertEquals("done", ShellBridgeEditorPolicy.action(1, 6, "正文", "body"));
        assertEquals("next", ShellBridgeEditorPolicy.action(1, 5, "正文", "body"));
        for(int action:new int[]{0,1,2,3,4,7}) assertEquals("",ShellBridgeEditorPolicy.action(1, action,"正文","body"));
    }
    @Test public void noEnterActionAndSensitiveEditorsRejectAction() throws Exception {
        assertEquals("",ShellBridgeEditorPolicy.action(1,6|0x40000000,"正文","body"));
        for(int type:new int[]{0,0x81,0x91,0xe1,0x12}) assertFalse(ShellBridgeEditorPolicy.inputAllowed(type,"",""));
        for(String hint:new String[]{"验证码","OTP","verification code","支付密码","银行卡号","手机号"})
            assertFalse(hint,ShellBridgeEditorPolicy.inputAllowed(1,hint,"field"));
    }
    @Test public void fieldNamesAlsoIdentifyOtpAndCredentials() throws Exception {
        for(String field:new String[]{"otp_code","verificationCode","password","credit_card","phone_number"})
            assertFalse(field,ShellBridgeEditorPolicy.inputAllowed(1,"",field));
    }
    @Test public void replacementTextHasBoundedUtf16Length() throws Exception {
        assertTrue(ShellBridgeEditorPolicy.textAllowed("中文🙂\n第二行"));
        assertTrue(ShellBridgeEditorPolicy.textAllowed(""));
        assertFalse(ShellBridgeEditorPolicy.textAllowed(null));
        assertFalse(ShellBridgeEditorPolicy.textAllowed("a".repeat(8001)));
        assertFalse(ShellBridgeEditorPolicy.textAllowed("a\u0000b"));
    }
}
