package com.qwen.tts.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.GenerationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryMigrationTest {
    @Test fun upgradePreservesVoicesAndOriginalHistoryAndCanSaveBothVariants() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE voice_profiles (voiceId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, referenceWavPath TEXT NOT NULL, speakerEmbeddingPath TEXT NOT NULL, durationMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE generations (generationId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, voiceId TEXT, voiceName TEXT NOT NULL, wavPath TEXT NOT NULL, sampleRate INTEGER NOT NULL, sampleCount INTEGER NOT NULL, synthesisMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("INSERT INTO voice_profiles VALUES ('voice-1','旧音色','reference.wav','embedding.bin',5000,100)")
            old.execSQL("INSERT INTO generations VALUES (7,'旧配音','voice-1','旧音色','original.wav',24000,24001,1200,100)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, QwenDatabase::class.java, name).addMigrations(QwenDatabase.MIGRATION_1_2, QwenDatabase.MIGRATION_2_3).build()
        try {
            val dao = db.qwenDao()
            assertEquals("旧音色", dao.getVoice("voice-1")!!.name)
            val originalVoice = dao.getVoice("voice-1")!!
            assertNull(originalVoice.iclPromptPath)
            assertEquals(1, dao.attachIcl("voice-1", "逐字稿", "icl.bin"))
            assertEquals(originalVoice.copy(referenceText = "逐字稿", iclPromptPath = "icl.bin"), dao.getVoice("voice-1"))
            val old = dao.getGeneration(7)!!
            assertEquals("original.wav", old.wavPath); assertNull(old.denoisedWavPath)
            assertEquals(1, dao.attachDenoised(7, old.wavPath, "clean.wav", 48000, 48002, 200, "test-model"))
            assertEquals(0, dao.attachDenoised(7, old.wavPath, "overwrite.wav", 48000, 48002, 200, "test-model"))
            val updated = dao.getGeneration(7)!!
            assertEquals(old.copy(denoisedWavPath = "clean.wav", denoisedSampleRate = 48000, denoisedSampleCount = 48002, denoiseMillis = 200, denoiseModel = "test-model"), updated)
            dao.deleteGeneration(updated)
            assertEquals(0, dao.attachDenoised(7, old.wavPath, "deleted.wav", 48000, 48002, 200, "test-model"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun freshDatabaseAllowsOnlyMatchingOriginalToReceiveDenoisedVersion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), QwenDatabase::class.java).build()
        try {
            val dao = db.qwenDao()
            val id = dao.insertGeneration(GenerationEntity(text = "新配音", voiceId = null, voiceName = "默认", wavPath = "source.wav", sampleRate = 24000, sampleCount = 24000, synthesisMillis = 100))
            assertEquals(0, dao.attachDenoised(id, "other-source.wav", "clean.wav", 48000, 48000, 1, "test-model"))
            assertNull(dao.getGeneration(id)!!.denoisedWavPath)
            assertEquals(1, dao.attachDenoised(id, "source.wav", "clean.wav", 48000, 48000, 1, "test-model"))
        } finally { db.close() }
    }
}
