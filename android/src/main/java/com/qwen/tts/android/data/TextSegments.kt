package com.qwen.tts.android.data

/** Keep each generation comfortably below the native audio-token limit. */
internal object TextSegments {
    fun split(text: String, maximum: Int = 160): List<String> {
        require(maximum >= 4)
        var remaining = text.trim()
        val result = mutableListOf<String>()
        while (remaining.length > maximum) {
            val punctuation = remaining.lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', ';', '；', '\n', '，', ',', ' '), maximum - 1)
            var boundary = if (punctuation >= maximum / 2) punctuation + 1 else maximum
            if (remaining[boundary - 1].isHighSurrogate()) boundary--
            remaining.substring(0, boundary).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            remaining = remaining.substring(boundary).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }
}
