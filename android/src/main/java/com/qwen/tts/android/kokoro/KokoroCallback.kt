package com.qwen.tts.android.kokoro

import java.util.concurrent.atomic.AtomicBoolean
import androidx.annotation.Keep

/** JNI resolves invoke(float[]):Integer, not Function1.invoke(Object):Object.
 * Kotlin's invokedynamic lambdas expose only the erased method. Keep this as
 * an explicit class, including when a future release enables code shrinking.
 */
@Keep
internal class KokoroGenerationCallback(private val cancelled: AtomicBoolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (cancelled.get()) 0 else 1
}

internal fun kokoroCallback(cancelled: AtomicBoolean): (FloatArray) -> Int =
    KokoroGenerationCallback(cancelled).also { callback ->
        // Detect an incompatible build before native code performs GetMethodID.
        // A pending JNI lookup exception cannot be handled by runCatching.
        check(runCatching {
            callback.javaClass.getMethod("invoke",FloatArray::class.java).returnType == Int::class.javaObjectType
        }.getOrDefault(false)) { "轻量配音回调接口不兼容，请更新应用。" }
    }
