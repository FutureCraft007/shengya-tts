package com.qwen.tts.android.api

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input() = JSONObject().put("requestId", UUID.randomUUID().toString()).put("text", "并发测试").put("voice", "voice-test")
    private fun awaitState(jobs: ApiJobs, id: String, state: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (jobs.get(id)?.getString("state") != state && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(state, jobs.get(id)?.getString("state"))
    }

    @Test fun sixJobsActuallyOverlapAndSeventhWaitsWithoutMixingResults() {
        val started = CountDownLatch(6)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        ApiJobs(temp.newFolder(), 6) { job, _, progress ->
            calls.incrementAndGet(); started.countDown(); progress(31, job.getString("id"))
            check(release.await(5, TimeUnit.SECONDS))
            JSONObject().put("owner", job.getString("id"))
        }.use { jobs ->
            try {
                val ids = (1..6).map { jobs.submit(input()).getString("id") }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertEquals(6, jobs.status().getInt("activeJobs"))
                val seventh = jobs.submit(input()).getString("id")
                assertEquals("queued", jobs.get(seventh)!!.getString("state"))
                assertEquals(6, calls.get())
                ids.forEach { assertEquals(it, jobs.get(it)!!.getString("message")) }
                release.countDown()
                (ids + seventh).forEach { id -> awaitState(jobs, id, "success"); assertEquals(id, jobs.get(id)!!.getString("owner")) }
            } finally { release.countDown() }
        }
    }

    @Test fun cancellingOneRunningJobDoesNotCancelItsFiveSiblings() {
        val started = CountDownLatch(6); val release = CountDownLatch(1)
        ApiJobs(temp.newFolder(), 6) { _, cancelled, _ ->
            started.countDown()
            while (!cancelled() && !release.await(5, TimeUnit.MILLISECONDS)) { }
            JSONObject()
        }.use { jobs ->
            try {
                val ids = (1..6).map { jobs.submit(input()).getString("id") }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                jobs.cancel(ids.first()); awaitState(jobs, ids.first(), "cancelled")
                ids.drop(1).forEach { assertEquals("running", jobs.get(it)!!.getString("state")) }
                release.countDown(); ids.drop(1).forEach { awaitState(jobs, it, "success") }
            } finally { release.countDown() }
        }
    }

    @Test fun raisingConcurrencyStartsQueuedWorkAndPreservesRequestIdentity() {
        val started = CountDownLatch(6); val release = CountDownLatch(1); val calls = AtomicInteger()
        ApiJobs(temp.newFolder(), 1) { _, _, _ -> calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); JSONObject() }.use { jobs ->
            try {
                val request = input()
                val first = jobs.submit(request)
                repeat(5) { jobs.submit(input()) }
                assertEquals(first.getString("id"), jobs.submit(request).getString("id"))
                jobs.setConcurrency(6)
                assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(6, calls.get())
                assertEquals(6, jobs.status().getInt("concurrency"))
                jobs.setConcurrency(1)
                val queued = jobs.submit(input()).getString("id")
                assertEquals("queued", jobs.get(queued)!!.getString("state"))
                release.countDown(); awaitState(jobs, queued, "success")
            } finally { release.countDown() }
        }
    }

    @Test fun capacityAndConcurrencyBoundsAreEnforced() {
        val release = CountDownLatch(1)
        ApiJobs(temp.newFolder(), 6) { _, _, _ -> release.await(5, TimeUnit.SECONDS); JSONObject() }.use { jobs ->
            try {
                repeat(16) { jobs.submit(input()) }
                try { jobs.submit(input()); fail("queue should be full") } catch (_: IllegalStateException) { }
                listOf(0, 7, -1).forEach { bad -> try { jobs.setConcurrency(bad); fail() } catch (_: IllegalArgumentException) { } }
                assertEquals(6, jobs.status().getInt("concurrency"))
            } finally { release.countDown() }
        }
    }

    @Test fun stoppingCancelsAllSixActiveJobsAndQueuedJobsWithoutRestartingThem() {
        val directory = temp.newFolder(); val started = CountDownLatch(6)
        val jobs = ApiJobs(directory, 6) { _, cancelled, _ -> started.countDown(); while (!cancelled()) Thread.sleep(5); JSONObject() }
        val ids = (1..8).map { jobs.submit(input()).getString("id") }
        assertTrue(started.await(5, TimeUnit.SECONDS)); jobs.close()
        ids.forEach { assertEquals("cancelled", jobs.get(it)!!.getString("state")) }
        ApiJobs(directory, 6) { _, _, _ -> fail("must not restart old work"); JSONObject() }.use { restored ->
            ids.forEach { assertEquals("cancelled", restored.get(it)!!.getString("state")) }
        }
    }

    @Test fun loweringToOneLetsCurrentWorkFinishButSerializesTheQueuedBatch() {
        val started = CountDownLatch(6); val release = CountDownLatch(1)
        val wave = AtomicInteger(); val laterActive = AtomicInteger(); val laterPeak = AtomicInteger()
        ApiJobs(temp.newFolder(), 6) { _, _, _ ->
            if (wave.incrementAndGet() <= 6) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            else {
                val active = laterActive.incrementAndGet()
                laterPeak.updateAndGet { maxOf(it, active) }
                Thread.sleep(20); laterActive.decrementAndGet()
            }
            JSONObject()
        }.use { jobs ->
            try {
                repeat(6) { jobs.submit(input()) }; assertTrue(started.await(5, TimeUnit.SECONDS))
                val queued = (1..6).map { jobs.submit(input()).getString("id") }
                jobs.setConcurrency(1); release.countDown()
                queued.forEach { awaitState(jobs, it, "success") }
                assertEquals(1, laterPeak.get())
            } finally { release.countDown() }
        }
    }
}
