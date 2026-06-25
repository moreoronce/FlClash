package com.follow.clash.service

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object OnDemandDiagnostics {
    private const val TAG = "FlClashOnDemand"
    private const val FILE_NAME = "on-demand-diagnostics.log"
    private const val MAX_ENTRIES = 200

    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val throttledAt = mutableMapOf<String, Long>()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        if (entries.isEmpty()) {
            file?.takeIf { it.exists() }
                ?.readLines()
                ?.takeLast(MAX_ENTRIES)
                ?.forEach(entries::addLast)
        }
        record("diagnostics initialized file=${file?.absolutePath.orEmpty()}")
    }

    @Synchronized
    fun record(message: String) {
        val line = "${formatter.format(Date())} $message"
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(line)
        runCatching {
            file?.writeText(entries.joinToString(separator = "\n", postfix = "\n"))
        }
        Log.i(TAG, line)
    }

    @Synchronized
    fun recordThrottled(key: String, message: String, intervalMillis: Long = 2000L) {
        val now = System.currentTimeMillis()
        val previous = throttledAt[key] ?: 0L
        if (now - previous < intervalMillis) {
            return
        }
        throttledAt[key] = now
        record(message)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
