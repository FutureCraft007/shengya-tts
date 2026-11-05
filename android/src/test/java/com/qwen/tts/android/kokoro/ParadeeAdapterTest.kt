package com.qwen.tts.android.kokoro

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ParadeeAdapterTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun englishValidationDoesNotSilentlyDropUnknownWords() {
        val lexicon=temporary.newFile().apply {writeText("hello h e l o\none w\ntwo t\n")}
        assertEquals("hello one two",ParadeeAdapter.prepareText("hello 12",lexicon))
        for(text in listOf("你好","unlistedword","...")) {
            try {ParadeeAdapter.prepareText(text,lexicon);fail("Must reject unsupported text")}
            catch(_:IllegalArgumentException){}
        }
    }
    @Test fun tinyModelHasOneEnglishVoice() {
        val models=KokoroModels(ApplicationProvider.getApplicationContext<Application>(),"paradee")
        assertEquals(8461438L,models.spec.getLong("bytes"));assertEquals(1,models.voices.size)
        assertEquals("en",models.voices.single().language)
    }
    @Test fun officialModelProducesSameAdapterBytesAndImports() {
        val file=System.getenv("SHENGYA_PARADEE_TEST_MODEL")?.let(::File)
        assumeTrue("Optional official model fixture",file?.isFile==true)
        val models=KokoroModels(ApplicationProvider.getApplicationContext<Application>(),"paradee")
        try {
            models.import(file!!.inputStream(),AtomicBoolean(false));assertTrue(models.ready())
            val adapted=File(models.directory,"model.int8.onnx").readBytes()
            val hash=MessageDigest.getInstance("SHA-256").digest(adapted).joinToString(""){"%02x".format(it.toInt() and 255)}
            assertEquals(models.spec.getString("adaptedSha256"),hash)
        } finally {
            val context=ApplicationProvider.getApplicationContext<Application>()
            check(models.directory.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
            models.directory.deleteRecursively()
        }
    }
}
