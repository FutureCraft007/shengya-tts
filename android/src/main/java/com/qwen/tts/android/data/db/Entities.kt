package com.qwen.tts.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey val voiceId: String,
    val name: String,
    val referenceWavPath: String,
    val speakerEmbeddingPath: String,
    val durationMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val referenceText: String? = null,
    val iclPromptPath: String? = null,
)

@Entity(tableName = "generations")
data class GenerationEntity(
    @PrimaryKey(autoGenerate = true) val generationId: Long = 0,
    val text: String,
    val voiceId: String?,
    val voiceName: String,
    val wavPath: String,
    val sampleRate: Int,
    val sampleCount: Int,
    val synthesisMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val denoisedWavPath: String? = null,
    val denoisedSampleRate: Int? = null,
    val denoisedSampleCount: Int? = null,
    val denoiseMillis: Long? = null,
    val denoiseModel: String? = null,
)
