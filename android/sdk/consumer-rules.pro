# Native entry points and the documented plugin contract survive application R8.
-keep class dev.doppel.sdk.ShellBridgeMain { public static void main(java.lang.String[]); }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
# JNA's optional desktop window helpers are never used on Android.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
-keep class ai.onnxruntime.** { *; }
-keep public interface dev.doppel.sdk.NativePlugin { *; }
-keep public class dev.doppel.sdk.PluginDescriptor { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
# The reader exposes only its public-network request method to the isolated page.
-keepclassmembers class dev.doppel.sdk.ReaderService$* {
    @android.webkit.JavascriptInterface <methods>;
}

# PdfBox-Android 2.0.27.0 supports optional JP2Android JPEG 2000 decoding.
# See TomRoush/PdfBox-Android v2.0.27.0, filter/JPXFilter.java's JP2ForAndroid docs.
# JPXFilter.readJPX checks Class.forName and throws MissingImageReaderException
# when absent. ChatDocumentText uses PDFTextStripper, not PDF image rendering.
# Suppress only this optional class; do not mask unrelated PDFBox/link errors.
-dontwarn com.gemalto.jp2.JP2Decoder
