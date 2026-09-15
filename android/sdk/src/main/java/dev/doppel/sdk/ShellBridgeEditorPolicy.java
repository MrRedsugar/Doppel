package dev.doppel.sdk;
import java.util.regex.Pattern;
final class ShellBridgeEditorPolicy {
    private static final Pattern PRIVATE=Pattern.compile("password|passcode|otp|verification.?code|security.?code|credit.?card|bank.?card|phone.?number|验证码|校验码|密码|口令|银行卡|信用卡|手机号",Pattern.CASE_INSENSITIVE);
    static boolean inputAllowed(int type,String hint,String field) {
        int category=type & 15, variation=type & 0xff0;
        if(category!=1 && category!=2) return false;
        if(category==1 && (variation==0x80 || variation==0x90 || variation==0xe0) || category==2 && variation==0x10) return false;
        return !PRIVATE.matcher(String.valueOf(hint)+" "+String.valueOf(field)).find();
    }
    static String action(int type,int options,String hint,String field) {
        if(!inputAllowed(type,hint,field) || (options & 0x40000000)!=0) return "";
        int action=options & 0xff;
        return action==6 ? "done" : action==5 ? "next" : "";
    }
    static boolean textAllowed(String text) { return text!=null && text.length()<=8000 && text.indexOf('\0')<0; }
}
