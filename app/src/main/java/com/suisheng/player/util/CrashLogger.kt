package com.suisheng.player.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.suisheng.player.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions to a small text file so a crash that only happens on a
 * specific head unit can be diagnosed without a PC attached.
 *
 * Why this exists: the app previously had no crash reporting at all, so an intermittent
 * crash (for example "starts fine right after install, crashes when the head unit is
 * powered on again hours later") could only be guessed at. Everything here is written
 * defensively — a failure inside the handler must never replace the original crash.
 */
object CrashLogger {
    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 3

    /** Elapsed time of this process start, used to report process uptime in the dump. */
    private var processStartElapsed = SystemClock.elapsedRealtime()

    fun install(context: Context) {
        val app = context.applicationContext
        processStartElapsed = SystemClock.elapsedRealtime()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        runCatching {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { write(app, thread, error) }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun latestFile(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }
    }

    fun readLatest(context: Context): String? =
        latestFile(context)?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, DIR_NAME).listFiles()?.forEach { it.delete() }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        file.writeText(buildReport(thread, error))
        // Keep only the newest few so the partition cannot fill up.
        dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("==== 随声 崩溃报告 ====")
        appendLine("时间: $wall")
        appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("线程: ${thread.name}")
        appendLine("进程存活: ${(SystemClock.elapsedRealtime() - processStartElapsed) / 1000} 秒")
        appendLine("开机时长: ${SystemClock.elapsedRealtime() / 1000} 秒")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        appendLine("---- 调用栈 ----")
        appendLine(error.stackTraceToString())
    }
}
