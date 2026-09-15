package dev.doppel.developer

import dev.doppel.sdk.ShellBridgeClient
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Test-only bridge to real SDK internals; no invisible Kotlin types in test bytecode signatures. */
internal object ShellBridgeSdkReflection {
    private fun sdk(name: String): Class<*> = Class.forName("dev.doppel.sdk.$name", true, ShellBridgeClient::class.java.classLoader)
    private val frameClass by lazy { sdk("VisualFrame") }
    private val gestureClass by lazy { sdk("VisualGesture") }
    private val pixelsClass by lazy { sdk("VisualPixels") }
    private val permitsClass by lazy { sdk("VisualGesturePermits") }
    private val issueMethod by lazy { permitsClass.getMethod("issue", String::class.java, gestureClass, String::class.java,
        Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType) }
    private val revokeMethod by lazy { permitsClass.getMethod("revoke", String::class.java) }
    private val compareMethod by lazy { pixelsClass.getMethod("compare", pixelsClass, gestureClass) }
    private val pixelConstructor by lazy { pixelsClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java) }
    private val diagnosticMethod by lazy { sdk("ShellBridgeDiagnostic").getMethod("sanitize", JSONObject::class.java) }

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    private fun parse(type: Class<*>, value: JSONObject): Any {
        val companion = type.getField("Companion").get(null)
        return requireNotNull(invoke(companion.javaClass.getMethod("parse", JSONObject::class.java), companion, value))
    }
    private fun json(value: Any): JSONObject = invoke(value.javaClass.getMethod("json"), value) as JSONObject

    /** Resolve all used members before setup changes or a device action can begin. */
    fun verifyContract() {
        for (type in listOf(frameClass, gestureClass)) {
            val companion = type.getField("Companion").get(null)
            check(companion.javaClass.getMethod("parse", JSONObject::class.java).returnType == type)
            check(type.getMethod("json").returnType == JSONObject::class.java)
        }
        check(issueMethod.returnType == String::class.java)
        check(revokeMethod.returnType == Void.TYPE)
        check(permitsClass.getField("INSTANCE").get(null).javaClass == permitsClass)
        check(pixelConstructor.declaringClass == pixelsClass)
        check(compareMethod.returnType.getMethod("json").returnType == JSONObject::class.java)
        check(diagnosticMethod.returnType == JSONObject::class.java)
    }

    fun frame(value: JSONObject): JSONObject = json(parse(frameClass, value))
    fun gesture(value: JSONObject): Any = parse(gestureClass, value)
    fun gestureJson(value: Any): JSONObject { check(gestureClass.isInstance(value)); return json(value) }
    fun issue(runId: String, gesture: Any, mode: String, approved: Boolean): String {
        check(gestureClass.isInstance(gesture))
        return invoke(issueMethod, permitsClass.getField("INSTANCE").get(null), runId, gesture, mode, approved, System.currentTimeMillis()) as String
    }
    fun revoke(runId: String) { invoke(revokeMethod, permitsClass.getField("INSTANCE").get(null), runId) }
    fun pixels(width: Int, height: Int, argb: IntArray): Any = try {
        pixelConstructor.newInstance(width, height, argb)
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    fun comparePixels(before: Any, after: Any, gesture: Any): JSONObject {
        check(pixelsClass.isInstance(before) && pixelsClass.isInstance(after) && gestureClass.isInstance(gesture))
        return json(requireNotNull(invoke(compareMethod, before, after, gesture)))
    }
    fun diagnostic(value: JSONObject?): JSONObject? = invoke(diagnosticMethod, null, value) as JSONObject?
}
