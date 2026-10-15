package com.nerfherder.walkthor.data

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * One low-priority thread for WalkThor's heavier background jobs (artwork matching), so they
 * always give way to the game: Android's scheduler runs the lowest-priority threads only when
 * nothing more important needs the CPU.
 */
object Background {
    val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            task.run()
        }, "walkthor-background").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
