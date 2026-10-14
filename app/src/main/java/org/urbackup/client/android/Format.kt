package org.urbackup.client.android

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Texts for the status card and the notification. */
object Format {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v < 10) String.format(Locale.getDefault(), "%.1f %s", v, units[i])
        else String.format(Locale.getDefault(), "%.0f %s", v, units[i])
    }

    fun files(n: Int): String = String.format(Locale.getDefault(), "%,d %s", n, if (n == 1) "file" else "files")

    fun backupTitle(action: String): String = when (action) {
        "FULL" -> "Full backup"
        "INCR" -> "Incremental backup"
        else -> "Backup"
    }

    /** e.g. "45% · 190 MB of 421 MB · 4.3 MB/s · about 1 min left" */
    fun progressLine(s: SessionState): String {
        if (s.percentDone < 0) return "Preparing the file list…"
        val parts = mutableListOf("${s.percentDone}%")
        if (s.totalBytes > 0 && s.doneBytes >= 0) parts += "${bytes(s.doneBytes)} of ${bytes(s.totalBytes)}"
        val bytesPerSecond = s.speedBps
        if (bytesPerSecond > 0) parts += "${bytes(bytesPerSecond.toLong())}/s"
        // the server rounds its estimate up to at least 61s, so prefer our own when the speed is known
        val leftMs = when {
            bytesPerSecond > 0 && s.totalBytes > s.doneBytes -> ((s.totalBytes - s.doneBytes) / bytesPerSecond * 1000).toLong()
            s.etaMs > 0 -> s.etaMs
            else -> -1
        }
        if (leftMs > 0) parts += "about ${duration(leftMs)} left"
        return parts.joinToString(" · ")
    }

    fun duration(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return when {
            ms < 60_000 -> "${(ms + 999) / 1000} s"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    fun phaseText(s: SessionState): String = when (s.phase) {
        Phase.IDLE -> ""
        Phase.STARTING -> "Starting…"
        Phase.CONNECTING -> if (s.serverAddress.isNotEmpty()) "Connecting to ${s.serverAddress}…" else "Connecting to the server…"
        Phase.WAITING_FOR_SERVER -> "Waiting for the server…"
        Phase.CONNECTED -> when {
            s.backupDone && s.backupFailed -> "Backup failed"
            s.backupDone -> "Backup finished"
            else -> "Checking with the server…"
        }
        Phase.BACKING_UP -> backupTitle(s.action)
    }

    /** "2 hours ago" */
    fun ago(time: Long): String {
        val now = System.currentTimeMillis()
        if (now - time < DateUtils.MINUTE_IN_MILLIS) return "just now"
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    /** "Oct 14, 7:33 PM" */
    fun dateTime(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
}
