package com.qwen.tts.android.kokoro

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class KokoroModelsTest {
    @Test fun verifiedOfficialArchiveImportsWithoutLegacyFrontendData() {
        val archive=System.getenv("SHENGYA_KOKORO_TEST_ARCHIVE")?.let(::File)
        assumeTrue("Optional real model fixture",archive?.isFile==true)
        val context=ApplicationProvider.getApplicationContext<Application>()
        val models=KokoroModels(context)
        try {
            models.import(archive!!.inputStream(),AtomicBoolean(false))
            assertTrue(models.ready())
            assertFalse(File(models.directory,"espeak-ng-data").exists())
            assertFalse(File(models.directory,"dict").exists())
        } finally {
            check(models.directory.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
            models.directory.parentFile!!.deleteRecursively()
        }
    }
    @Test fun officialCatalogueContainsOneHundredChineseVoices() {
        val models=KokoroModels(ApplicationProvider.getApplicationContext<Application>())
        assertEquals(103,models.voices.size)
        assertEquals((0..102).toSet(),models.voices.map { it.id }.toSet())
        assertEquals(100,models.voices.count { it.language=="zh" })
        assertEquals(3,models.voices.count { it.language=="en" })
        assertEquals(147052523L,models.spec.getLong("bytes"))
    }
    @Test fun missingModelIsNotReportedAsReadyAndNativeIsNotLoaded() {
        val models=KokoroModels(ApplicationProvider.getApplicationContext<Application>())
        assertFalse(models.ready())
        try { models.createTts(4);fail("Missing model must be rejected") }
        catch(error:IllegalStateException){assertTrue(error.message!!.contains("下载"))}
    }
}
