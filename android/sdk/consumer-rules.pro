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
