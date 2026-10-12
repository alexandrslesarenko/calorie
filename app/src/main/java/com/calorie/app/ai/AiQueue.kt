package com.calorie.app.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calorie.app.R
import com.calorie.app.data.AiCached
import com.calorie.app.data.AiKeys
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Pending
import com.calorie.app.data.Prefs
import com.calorie.app.data.Source
import com.calorie.app.logic.AiParser
import com.calorie.app.logic.AiResult
import com.calorie.app.logic.Recognizer
import com.calorie.app.logic.Skipped
import com.calorie.app.logic.askInOrder
import com.calorie.app.logic.Retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** How many recent Claude answers to keep in the cache. */
private const val AI_CACHE_KEEP = 300
private const val WORK_NAME = "ai_queue"

/** One recognition request: enough to send it again later. text - description or photo hint. */
class AiRequest(
    val source: Source,
    val text: String,
    val language: String,
    val jpeg: ByteArray?,
    val cacheKey: String,
    val useCache: Boolean,
) {
    fun send(c: FoodRecognizer): AiResponse =
        if (jpeg != null) c.recognizePhoto(jpeg, text.ifBlank { null }, language) else c.recognizeText(text, language)
}

/**
 * Parsed answer and its raw JSON (for the cache and the queue). by - who answered (null - the
 * cache); skipped - recognizers asked before it that turned out to be gone.
 */
class AiAnswer(
    val result: AiResult,
    val json: String,
    val fromCache: Boolean,
    val by: Recognizer? = null,
    val skipped: List<Skipped> = emptyList(),
)

/**
 * Claude requests with the answer cache, and the retry queue: a request that failed for a
 * temporary reason (overload, 429, no network) is saved in the pending table and resent by
 * AiQueueWorker, so the user does not have to type the description or take the photo again.
 */
object AiQueue {
    /** Photo of a queued request; kept until the answer goes to the diary or is dropped. */
    fun jpegFile(ctx: Context, id: Long) = File(File(ctx.filesDir, "pending"), "$id.jpg")

    /**
     * Answer from the cache or from a recognizer. The chosen recognizer is asked first; when its
     * model is gone (404) the next one with a key is asked, for this request only. Any other
     * failure ends the round: a busy service is waited for in the queue, not swapped. A new
     * answer is counted in the spending at the answering model's price and cached, except an
     * empty one ("no food"): that photo will most likely be retaken.
     */
    suspend fun ask(ctx: Context, keys: AiKeys, req: AiRequest): AiAnswer {
        val dao = FoodDb.get(ctx).dao()
        if (req.useCache) dao.aiCached(req.cacheKey)?.let { return AiAnswer(AiParser.parse(it.json), it.json, fromCache = true) }
        val prefs = Prefs(ctx)
        val order = Recognizer.order(prefs.recognizer) { keys.has(it) }
        if (order.isEmpty()) throw AiException(AiFailure.BAD_KEY)
        val a = askInOrder(
            order,
            skips = { it is AiException && it.failure.skipsToNext },
            onSkip = { rec, e -> if ((e as AiException).failure == AiFailure.MODEL_GONE) prefs.setGone(rec, System.currentTimeMillis()) },
            failed = { rec, e -> (e as? AiException)?.by = rec },
        ) { rec -> withContext(Dispatchers.IO) { FoodRecognizer.create(rec, keys[rec.provider]!!).use(req::send) } }
        val r = a.value
        prefs.addUsage(r.usage.inputTokens, r.usage.outputTokens, a.by)
        if (r.result.items.isNotEmpty()) {
            dao.putAiCached(AiCached(req.cacheKey, r.json, System.currentTimeMillis()))
            dao.trimAiCache(AI_CACHE_KEEP)
        }
        return AiAnswer(r.result, r.json, fromCache = false, by = a.by, skipped = a.skipped)
    }

    /** Puts a request that failed with a retryable reason into the queue; returns the row id. */
    suspend fun enqueue(ctx: Context, req: AiRequest, day: Long, meal: String, failure: AiFailure, detail: String?): Long {
        val dao = FoodDb.get(ctx).dao()
        val now = System.currentTimeMillis()
        val id = dao.insertPending(
            Pending(
                day = day, meal = meal, source = req.source.key, text = req.text, language = req.language,
                cacheKey = req.cacheKey, useCache = req.useCache, state = Pending.WAITING, attempts = 1,
                since = now, nextTry = now + Retry.delayMs(1), failure = failure.name, detail = detail, json = null,
            )
        )
        if (req.jpeg != null) {
            withContext(Dispatchers.IO) {
                val f = jpegFile(ctx, id)
                f.parentFile?.mkdirs()
                f.writeBytes(req.jpeg)
            }
        }
        schedule(ctx)
        return id
    }

    suspend fun retryNow(ctx: Context, id: Long) {
        FoodDb.get(ctx).dao().pendingRetryNow(id, System.currentTimeMillis())
        schedule(ctx)
    }

    /** Drops a queued request with its photo: after saving to the diary or on the user's wish. */
    suspend fun delete(ctx: Context, id: Long) {
        // File first: the row going away closes the screen whose scope runs this.
        withContext(Dispatchers.IO) { jpegFile(ctx, id).delete() }
        FoodDb.get(ctx).dao().deletePending(id)
    }

    /** Photo of a queued request; null for text or if the file is gone. */
    suspend fun jpeg(ctx: Context, p: Pending): ByteArray? =
        if (p.source != Source.PHOTO.key) null
        else withContext(Dispatchers.IO) { runCatching { jpegFile(ctx, p.id).readBytes() }.getOrNull() }

    /**
     * Starts the worker at the earliest retry time. While it runs it picks up new rows itself,
     * and replacing it then could drop an answer that is already paid for.
     */
    suspend fun schedule(ctx: Context) {
        if (AiQueueWorker.running) return
        start(ctx, ExistingWorkPolicy.REPLACE)
    }

    internal suspend fun start(ctx: Context, policy: ExistingWorkPolicy) {
        val next = FoodDb.get(ctx).dao().nextPendingTry() ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0)
        val work = OneTimeWorkRequestBuilder<AiQueueWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, work)
    }
}

/** Resends due requests from the queue; plans its next run by the earliest retry time. */
class AiQueueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        @Volatile var running = false
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = FoodDb.get(ctx).dao()
        running = true
        try {
            // Rows added while a round runs are due at once or later: loop until none is due.
            while (true) {
                val due = dao.pendingDue(System.currentTimeMillis())
                if (due.isEmpty()) break
                val keys = AiKeys.load(ctx)
                // After one temporary failure the rest would fail too: they wait with it.
                var downUntil = 0L
                for (p in due) {
                    if (downUntil > 0) dao.pendingPostpone(p.id, downUntil)
                    else downUntil = attempt(ctx, keys, p)
                }
            }
        } finally {
            running = false
        }
        // Appended, not replaced: this worker is still running.
        AiQueue.start(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    /** One try of one row; every path moves the row on. Returns the next try if the service is down, else 0. */
    private suspend fun attempt(ctx: Context, keys: AiKeys, p: Pending): Long {
        val dao = FoodDb.get(ctx).dao()
        if (!keys.any) {
            dao.pendingFailed(p.id, AiFailure.BAD_KEY.name, null)
            return 0
        }
        val jpeg = AiQueue.jpeg(ctx, p)
        if (p.source == Source.PHOTO.key && jpeg == null) {
            dao.pendingFailed(p.id, null, ctx.getString(R.string.photo_unreadable))
            return 0
        }
        val req = AiRequest(Source.byKey(p.source), p.text, p.language, jpeg, p.cacheKey, p.useCache)
        try {
            val a = AiQueue.ask(ctx, keys, req)
            if (a.result.items.isEmpty()) dao.pendingFailed(p.id, null, a.result.note ?: ctx.getString(R.string.ai_nothing))
            else dao.pendingReady(p.id, a.json)
        } catch (e: AiException) {
            val now = System.currentTimeMillis()
            if (e.failure.retryable && !Retry.giveUp(p.since, now)) {
                val next = now + Retry.delayMs(p.attempts + 1)
                dao.pendingRetry(p.id, p.attempts + 1, next, e.failure.name, e.message)
                return next
            }
            if (e.failure == AiFailure.BAD_KEY) e.by?.let { Prefs(ctx).setKeyStatus(it.provider, Prefs.KEY_BAD) }
            dao.pendingFailed(p.id, e.failure.name, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.pendingFailed(p.id, null, e.message ?: e.javaClass.simpleName)
        }
        return 0
    }
}
