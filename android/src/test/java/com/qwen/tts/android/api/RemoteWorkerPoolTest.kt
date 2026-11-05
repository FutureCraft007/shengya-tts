package com.qwen.tts.android.api

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
class RemoteWorkerPoolTest {
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(condition())
    }

    private class FakeProcesses(context: Context) : ContextWrapper(context), AutoCloseable {
        val starts = CountDownLatch(6)
        val release = CountDownLatch(1)
        val unbound = AtomicInteger()
        val lanes = ConcurrentHashMap.newKeySet<String>()
        private val cancelled = ConcurrentHashMap.newKeySet<String>()
        private val native = Executors.newFixedThreadPool(6)
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val component = intent.component!!
            lanes.add(component.className)
            val remote = Messenger(Handler(Looper.getMainLooper()) { message ->
                val id = message.data.getString("id")!!
                if (message.what == WorkerMessages.CANCEL) cancelled.add(id)
                else if (message.what == WorkerMessages.RUN) {
                    val reply = message.replyTo!!
                    fun send(type: Int, payload: String) = reply.send(Message.obtain(null, type).apply {
                        data = Bundle().apply { putString("id", id); putString("payload", payload) }
                    })
                    native.execute {
                        starts.countDown()
                        send(WorkerMessages.PROGRESS, JSONObject().put("progress", 43).put("message", id).toString())
                        while (id !in cancelled && !release.await(5, TimeUnit.MILLISECONDS)) { }
                        if (id in cancelled) send(WorkerMessages.ERROR, "仅此任务已取消")
                        else send(WorkerMessages.RESULT, JSONObject().put("owner", id).put("lane", component.className).toString())
                    }
                }
                true
            })
            connection.onServiceConnected(component, remote.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound.incrementAndGet() }
        override fun close() { release.countDown(); native.shutdownNow() }
    }

    @Test fun sixIndependentBinderLanesOverlapKeepProgressAndResultsSeparateAndReleaseOnIdle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        FakeProcesses(app).use { context ->
            RemoteWorkerPool(context).use { pool ->
                assertTrue(context.lanes.isEmpty())
                val caller = Executors.newFixedThreadPool(6)
                try {
                    val progress = ConcurrentHashMap<String, String>()
                    val ids = (1..6).map { UUID.randomUUID().toString() }
                    val results = ids.map { id -> caller.submit<JSONObject> {
                        pool.execute(JSONObject().put("id", id), { false }) { _, message -> progress[id] = message }
                    } }
                    await { context.starts.count == 0L && progress.size == 6 }
                    assertEquals(6, context.lanes.size)
                    ids.forEach { assertEquals(it, progress[it]) }
                    assertTrue(results.none { it.isDone })
                    context.release.countDown()
                    await { results.all { it.isDone } }
                    ids.forEachIndexed { i, id -> assertEquals(id, results[i].get().getString("owner")) }
                    assertEquals(6, results.map { it.get().getString("lane") }.toSet().size)
                    shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
                    assertEquals(6, context.unbound.get())
                } finally { context.release.countDown(); caller.shutdownNow() }
            }
        }
    }

    @Test fun workerCancellationIsAddressedToOnlyOneRequestAndClosingReleasesEveryBinding() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        FakeProcesses(app).use { context ->
            val pool = RemoteWorkerPool(context)
            val caller = Executors.newFixedThreadPool(6)
            try {
                val flags = (1..6).map { AtomicBoolean() }
                val results = flags.map { flag -> caller.submit<JSONObject> { pool.execute(JSONObject().put("id", UUID.randomUUID().toString()), flag::get) { _, _ -> } } }
                await { context.starts.count == 0L }
                flags[0].set(true)
                await { results[0].isDone }
                assertTrue(results.drop(1).none { it.isDone })
                try { results[0].get(); fail() } catch (error: java.util.concurrent.ExecutionException) { assertTrue(error.cause!!.message!!.contains("仅此任务")) }
                context.release.countDown(); await { results.all { it.isDone } }
                results.drop(1).forEach { assertTrue(it.get().has("owner")) }
            } finally { pool.close(); caller.shutdownNow() }
            assertEquals(6, context.unbound.get())
        }
    }

    @Test fun manifestDeclaresSixDistinctNonExportedProcessesAndConfigurationDoesNotStartAService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val processes = RemoteWorkerPool.SERVICES.map { service ->
            val info = app.packageManager.getServiceInfo(ComponentName(app, service), 0)
            assertFalse(info.exported)
            assertTrue(info.processName.startsWith(app.packageName + ":qwen_worker_"))
            info.processName
        }
        assertEquals(6, processes.toSet().size)
        shadowOf(app).clearStartedServices()
        LocalApiService.configure(app, 6)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(6, LocalApiService.concurrency(app))
    }

    @Test fun actualWorkerMessengerReportsModelErrorsWithoutLoadingNativeCode() {
        val controller = Robolectric.buildService(QwenWorker1::class.java).create()
        val response = CompletableFutureResult()
        try {
            val remote = Messenger(controller.get().onBind(Intent()))
            val id = UUID.randomUUID().toString()
            remote.send(Message.obtain(null, WorkerMessages.RUN).apply {
                replyTo = Messenger(Handler(Looper.getMainLooper()) { message -> response.type = message.what; response.payload = message.data.getString("payload"); true })
                data = Bundle().apply { putString("id", id); putString("payload", JSONObject().put("model", LocalApiService.BASE).toString()) }
            })
            await { response.type == WorkerMessages.ERROR }
            assertTrue(response.payload!!.contains("模型"))
        } finally { controller.destroy() }
    }
    private class CompletableFutureResult { @Volatile var type = 0; @Volatile var payload: String? = null }
}
