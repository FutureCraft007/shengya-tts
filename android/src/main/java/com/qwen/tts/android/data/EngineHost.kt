package com.qwen.tts.android.data

import com.qwen.tts.studio.engine.QwenEngine
import kotlinx.coroutines.flow.MutableStateFlow

/** UI engine and coordination flags. API inference runs in separate worker processes. */
internal object EngineHost {
    val lock = Any()
    var engine: QwenEngine? = null
    var modelName: String? = null
    @Volatile var serviceEnabled = false
    val apiBusy = MutableStateFlow(false)
    val uiBusy = MutableStateFlow(false)

    fun control(block: () -> Unit): Boolean {
        if (uiBusy.value) return false
        uiBusy.value = true
        try {
            if (apiBusy.value) return false
            synchronized(lock) { block() }
            return true
        } finally { uiBusy.value = false }
    }

    fun close() = synchronized(lock) {
        engine?.close()
        engine = null
        modelName = null
    }
}
