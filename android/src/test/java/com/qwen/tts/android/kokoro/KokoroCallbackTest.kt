package com.qwen.tts.android.kokoro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class KokoroCallbackTest {
    // sherpa-onnx JNI uses GetMethodID("invoke", "([F)Ljava/lang/Integer;").
    // A generic Function1.invoke(Object):Object is insufficient, even though it
    // works when called directly from Kotlin.
    @Test fun nativeCallbackHasTypedBoxedSignature() {
        val callback=kokoroCallback(AtomicBoolean(false))
        val invoke=callback.javaClass.getMethod("invoke",FloatArray::class.java)
        assertEquals(Int::class.javaObjectType,invoke.returnType)
        assertEquals(1,invoke.invoke(callback,floatArrayOf(.1f)))
    }

    @Test fun nativeCallbackObservesCancellation() {
        val cancelled=AtomicBoolean(false)
        val callback=kokoroCallback(cancelled)
        assertEquals(1,callback(floatArrayOf()))
        cancelled.set(true)
        assertEquals(0,callback(floatArrayOf()))
    }
}
