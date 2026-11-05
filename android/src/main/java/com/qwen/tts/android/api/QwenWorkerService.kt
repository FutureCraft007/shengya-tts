package com.qwen.tts.android.api

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object WorkerMessages {
    const val RUN = 1
    const val CANCEL = 2
    const val PROGRESS = 3
    const val RESULT = 4
    const val ERROR = 5
}

/** Bound only after an explicitly started HTTP service receives a job. */
open class QwenWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var requestId: String? = null
    private var synthesizer: WorkerSynthesizer? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            WorkerMessages.CANCEL -> if (message.data.getString("id") == requestId) cancelled.set(true)
            WorkerMessages.RUN -> {
                val reply = message.replyTo ?: return@Handler true
                val id = message.data.getString("id") ?: return@Handler true
                fun send(type: Int, payload: String) {
                    runCatching { reply.send(Message.obtain(null, type).apply { data = Bundle().apply { putString("id", id); putString("payload", payload) } }) }
                }
                if (!busy.compareAndSet(false, true)) send(WorkerMessages.ERROR, "工作进程仍在运行另一条配音")
                else {
                    requestId = id
                    cancelled.set(false)
                    val input = message.data.getString("payload").orEmpty()
                    executor.execute {
                        val response = try {
                            val native = synthesizer ?: WorkerSynthesizer(this).also { synthesizer = it }
                            val result = native.synthesize(JSONObject(input), cancelled::get) { progress, text ->
                                send(WorkerMessages.PROGRESS, JSONObject().put("progress", progress).put("message", text).toString())
                            }
                            WorkerMessages.RESULT to result.toString()
                        } catch (error: Throwable) {
                            WorkerMessages.ERROR to (if (error is OutOfMemoryError) "工作进程内存不足，请降低并发数后重试" else error.message ?: "独立推理进程失败")
                        }
                        busy.set(false)
                        send(response.first, response.second)
                    }
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdownNow()
        // Unbinding an idle or stopped lane must also release the native backend
        // and model allocations, including an inference still inside native code.
        if (busy.get() && Application.getProcessName().startsWith("$packageName:qwen_worker_")) Process.killProcess(Process.myPid())
        else if (!busy.get()) { synthesizer?.close(); synthesizer = null }
        super.onDestroy()
    }
}

class QwenWorker1 : QwenWorkerService()
class QwenWorker2 : QwenWorkerService()
class QwenWorker3 : QwenWorkerService()
class QwenWorker4 : QwenWorkerService()
class QwenWorker5 : QwenWorkerService()
class QwenWorker6 : QwenWorkerService()
