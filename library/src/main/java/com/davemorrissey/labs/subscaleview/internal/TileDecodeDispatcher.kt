package com.davemorrissey.labs.subscaleview.internal

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared worker pool every [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView]
 * decodes on by default.
 *
 * Why not [kotlinx.coroutines.Dispatchers.Default]: that pool has one normal-priority thread per
 * core and is *per process*, so a page-flip burst (a base layer plus several tiles for each of
 * several views) ran as many full-speed JPEG decodes at once as there are cores. On a small
 * device that starves the UI/render threads (visible as dropped frames while scrolling) and
 * multiplies the transient memory of the decodes in flight. This pool is
 *  - small: two threads on a small Java heap, three otherwise, never more than half the cores;
 *  - slightly de-prioritised, so a decode never wins a core from the UI thread but still stays in
 *    the foreground scheduling group (full [Process.THREAD_PRIORITY_BACKGROUND] would pin it to
 *    a small share of the CPU and make tiles arrive visibly late);
 *  - shared by all views, so the concurrency limit is global rather than per view.
 *
 * Tiles that were evicted before a worker reaches them are skipped without decoding (see
 * `Tile.epoch`), so a long queue left behind by a fast fling costs almost nothing.
 */
internal object TileDecodeDispatcher {

    private const val SMALL_HEAP_BYTES = 256L * 1024 * 1024
    private const val MAX_THREADS = 3
    private const val KEEP_ALIVE_SECONDS = 20L

    /**
     * One idle-timeout thread for releasing decoders. A decoder may only be released once no decode
     * is running on it, which can take a while; doing that wait on the main thread froze scrolling
     * every time a page was recycled mid-decode.
     */
    val cleanup: Executor by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ThreadPoolExecutor(
            1,
            1,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { task -> Thread(task, "ssiv-cleanup").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    val instance: CoroutineDispatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val cores = Runtime.getRuntime().availableProcessors()
        val limit = if (Runtime.getRuntime().maxMemory() <= SMALL_HEAP_BYTES) 2 else MAX_THREADS
        val threads = (cores / 2).coerceIn(1, limit)
        val counter = AtomicInteger()
        val factory = ThreadFactory { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                    task.run()
                },
                "ssiv-decode-${counter.incrementAndGet()}",
            )
        }
        ThreadPoolExecutor(threads, threads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)
            .apply { allowCoreThreadTimeOut(true) }
            .asCoroutineDispatcher()
    }
}
