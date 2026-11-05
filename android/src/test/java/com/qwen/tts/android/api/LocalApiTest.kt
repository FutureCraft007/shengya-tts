package com.qwen.tts.android.api

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class LocalApiTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input(id: String = UUID.randomUUID().toString(), text: String = "这是接口测试") = JSONObject().put("requestId", id).put("text", text).put("voice", "voice-test")
    private fun waitFor(jobs: ApiJobs, id: String, state: String) {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (jobs.get(id)?.getString("state") != state && System.nanoTime()<deadline) Thread.sleep(5)
        assertEquals(state, jobs.get(id)?.getString("state"))
    }

    @Test fun retriesReuseOneJobAndModifiedRequestIsRejected() {
        val count = AtomicInteger()
        ApiJobs(temp.newFolder()) { _, _, _ -> count.incrementAndGet(); JSONObject().put("sampleRate", 24000) }.use { jobs ->
            val request = input()
            val first = jobs.submit(request)
            assertEquals(first.getString("id"), jobs.submit(request).getString("id"))
            waitFor(jobs, first.getString("id"), "success")
            assertEquals(1, count.get())
            assertEquals(first.getString("id"), jobs.findRequest(request.getString("requestId"))!!.getString("id"))
            try { jobs.submit(input(request.getString("requestId"), "改变了文案")); fail() } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun queueSerializesJobsAndCancellationDoesNotExecuteQueuedTask() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
        ApiJobs(temp.newFolder()) { _, _, _ -> count.incrementAndGet(); started.countDown(); release.await(5,TimeUnit.SECONDS); JSONObject() }.use { jobs ->
            val first = jobs.submit(input()).getString("id")
            assertTrue(started.await(5,TimeUnit.SECONDS))
            val second = jobs.submit(input()).getString("id")
            assertEquals("queued", jobs.get(second)!!.getString("state"))
            jobs.cancel(second); release.countDown()
            waitFor(jobs, first, "success"); waitFor(jobs, second, "cancelled")
            assertEquals(1,count.get())
        }
    }

    @Test fun interruptedJobFailsOnRestartWithoutAutomaticGeneration() {
        val directory = temp.newFolder()
        val id = UUID.randomUUID().toString()
        File(directory,"$id.json").writeText(input().put("id",id).put("state","running").put("signature","test").toString())
        val calls = AtomicInteger()
        ApiJobs(directory) { _, _, _ -> calls.incrementAndGet(); JSONObject() }.use { jobs ->
            assertEquals("failed",jobs.get(id)!!.getString("state")); assertEquals(0,calls.get())
        }
    }

    @Test fun httpRequiresTokenAndReturnsActualWaveBytesWithoutPrivatePath() {
        val wav = temp.newFile("audio.wav").apply { writeBytes("RIFFtestWAVE".toByteArray()+ByteArray(32)) }
        LoopbackServer(0,"test-token") { method,path,_ ->
            if (path == "/audio") LoopbackServer.Reply(file=wav)
            else LoopbackServer.Reply(json=JSONObject().put("method",method).put("service","test"))
        }.use { server ->
            fun connection(path: String, token: String? = "test-token") = (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection).apply {
                connectTimeout=2000;readTimeout=2000;if(token!=null)setRequestProperty("Authorization","Bearer $token")
            }
            val denied=connection("/health",null)
            assertEquals(401,denied.responseCode);denied.disconnect()
            val valid=connection("/health")
            assertEquals("test",JSONObject(valid.inputStream.bufferedReader().readText()).getString("service"));valid.disconnect()
            val audio=connection("/audio")
            assertArrayEquals(wav.readBytes(),audio.inputStream.readBytes());assertEquals("audio/wav",audio.contentType);audio.disconnect()
        }
    }

    @Test fun httpRejectsOversizedBodyAndStopsListeningAfterClose() {
        val calls=AtomicInteger()
        val server=LoopbackServer(0,"secret") { _,_,_ -> calls.incrementAndGet();LoopbackServer.Reply(json=JSONObject()) }
        val port=server.port
        java.net.Socket("127.0.0.1",port).use { socket ->
            socket.soTimeout=2000
            socket.getOutputStream().write("POST /jobs HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nContent-Length: 99999999\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().contains("400"))
        }
        assertEquals(0,calls.get());server.close()
        try { java.net.Socket("127.0.0.1",port).close();fail() } catch (_: java.io.IOException) {}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ManualServiceStartTest {
    @Test fun appAndTokenInitializationDoNotStartAnyService() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).clearStartedServices()
        LocalApiService.token(app)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(LocalApiService.state.value.running)
    }
    @Test fun serviceOnlyStartsAfterExplicitStartCall() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).clearStartedServices()
        LocalApiService.start(app,9001)
        val intent=shadowOf(app).nextStartedService
        assertEquals(LocalApiService::class.java.name,intent.component!!.className)
        assertEquals(9001,intent.getIntExtra("port",0))
        assertEquals(LocalApiService.ACTION_START,intent.action)
    }
    @Test fun actualAndroidServiceOnlyBindsAfterExplicitActionAndClosesItsSocket() {
        val controller=Robolectric.buildService(LocalApiService::class.java).create()
        val service=controller.get()
        try {
            service.onStartCommand(null,0,1)
            assertFalse(LocalApiService.state.value.running)
            val port=java.net.ServerSocket(0).use { it.localPort }
            service.onStartCommand(Intent(service,LocalApiService::class.java).setAction(LocalApiService.ACTION_START).putExtra("port",port),0,2)
            assertTrue(LocalApiService.state.value.message,LocalApiService.state.value.running)
            val connection=URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization","Bearer "+LocalApiService.token(service))
            val result=JSONObject(connection.inputStream.bufferedReader().readText())
            connection.disconnect()
            assertEquals("qwen-local-tts",result.getString("service"))
            assertEquals(1,result.getInt("protocol"))
        } finally { controller.destroy() }
        assertFalse(LocalApiService.state.value.running)
    }
}
