package com.qwen.tts.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class QwenDatabase : RoomDatabase() {
    abstract fun qwenDao(): QwenDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_profiles ADD COLUMN referenceText TEXT")
                db.execSQL("ALTER TABLE voice_profiles ADD COLUMN iclPromptPath TEXT")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN denoisedWavPath TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN denoisedSampleRate INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN denoisedSampleCount INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN denoiseMillis INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN denoiseModel TEXT")
            }
        }

        @Volatile
        private var INSTANCE: QwenDatabase? = null

        fun getDatabase(context: Context): QwenDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QwenDatabase::class.java,
                    "qwen_tts_database",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
