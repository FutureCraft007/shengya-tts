package com.qwen.tts.android.api

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiConfigurationTest {
    @Test fun authenticatedConfigurationUpdatesTheLiveLimitAndRejectsInvalidValuesWithoutStartingWorkers() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LocalApiService.state.value = ApiServiceState()
        shadowOf(app).clearStartedServices()
        LocalApiService.configure(app, 6)
        assertNull(shadowOf(app).nextStartedService)
        val controller = Robolectric.buildService(LocalApiService::class.java).create()
        val service = controller.get()
        val port = ServerSocket(0).use { it.localPort }
        fun request(path: String, payload: String? = null, token: String = LocalApiService.token(app)): Pair<Int, JSONObject> {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000; readTimeout = 2000
                setRequestProperty("Authorization", "Bearer $token")
                if (payload != null) {
                    requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(payload.toByteArray()) }
                }
            }
            try {
                val code = connection.responseCode
                return code to JSONObject((if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().readText())
            } finally { connection.disconnect() }
        }
        try {
            service.onStartCommand(Intent(service, LocalApiService::class.java).setAction(LocalApiService.ACTION_START).putExtra("port", port), 0, 1)
            val health = request("/health").second
            assertEquals(6, health.getInt("maxConcurrency")); assertEquals(6, health.getInt("concurrency"))
            assertEquals(0, health.getInt("activeJobs")); assertEquals("isolated-processes", health.getString("execution"))
            assertEquals(401, request("/config", "{\"concurrency\":1}", "incorrect-token").first)
            assertEquals(200, request("/config", "{\"concurrency\":2}").first)
            assertEquals(2, LocalApiService.concurrency(app)); assertEquals(2, request("/health").second.getInt("concurrency"))
            for (bad in listOf("0", "7", "1.5", "\"6\"")) assertEquals(400, request("/config", "{\"concurrency\":$bad}").first)
            assertEquals(2, request("/config").second.getInt("concurrency"))
        } finally { controller.destroy() }
        assertFalse(LocalApiService.state.value.running)
    }
}
