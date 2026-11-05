package com.qwen.tts.android.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QwenDao {
    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    fun observeVoices(): Flow<List<VoiceProfileEntity>>

    @Insert
    suspend fun insertVoice(voice: VoiceProfileEntity)

    @Query("UPDATE voice_profiles SET name = :name WHERE voiceId = :voiceId")
    suspend fun renameVoice(voiceId: String, name: String)

    @Query("SELECT * FROM voice_profiles WHERE voiceId = :voiceId")
    suspend fun getVoice(voiceId: String): VoiceProfileEntity?

    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    suspend fun listVoices(): List<VoiceProfileEntity>

    @Query("UPDATE voice_profiles SET referenceText = :text, iclPromptPath = :path WHERE voiceId = :id")
    suspend fun attachIcl(id: String, text: String, path: String): Int

    @Delete
    suspend fun deleteVoice(voice: VoiceProfileEntity)

    @Query("SELECT * FROM generations ORDER BY createdAt DESC")
    fun observeGenerations(): Flow<List<GenerationEntity>>

    @Insert
    suspend fun insertGeneration(generation: GenerationEntity): Long

    @Query("SELECT * FROM generations WHERE generationId = :generationId")
    suspend fun getGeneration(generationId: Long): GenerationEntity?

    @Query("""UPDATE generations SET denoisedWavPath = :path, denoisedSampleRate = :rate,
        denoisedSampleCount = :samples, denoiseMillis = :millis, denoiseModel = :model
        WHERE generationId = :id AND wavPath = :source AND denoisedWavPath IS NULL""")
    suspend fun attachDenoised(id: Long, source: String, path: String, rate: Int, samples: Int, millis: Long, model: String): Int

    @Delete
    suspend fun deleteGeneration(generation: GenerationEntity)
}
