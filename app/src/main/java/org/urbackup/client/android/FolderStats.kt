package org.urbackup.client.android

import java.io.File

/** What a backed-up folder contains right now. */
data class FolderStats(
    val exists: Boolean,
    val files: Int,
    val bytes: Long,
)

object FolderScanner {
    /** Counts the files below [path]. Can take a while for big folders; call it off the main thread. */
    fun scan(path: String): FolderStats {
        val root = File(path)
        if (!root.isDirectory) return FolderStats(exists = false, files = 0, bytes = 0)
        var files = 0
        var bytes = 0L
        root.walkTopDown()
            .onFail { _, _ -> }
            .filter { it.isFile }
            .forEach {
                files++
                bytes += it.length()
            }
        return FolderStats(exists = true, files = files, bytes = bytes)
    }
}
