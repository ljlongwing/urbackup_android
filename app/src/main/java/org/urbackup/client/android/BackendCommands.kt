package org.urbackup.client.android

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the backend's local command interface (127.0.0.1:35623), the same one
 * urbackupclientctl and the desktop GUI use. Each command is one connection:
 * a packet "<COMMAND>#pw=<password>[&args]", answered by one packet.
 * Packets are a 4 byte little endian length followed by the data.
 */
class BackendCommands(private val backend: Backend) {

    private fun request(command: String, args: String = "", change: Boolean = false, timeoutMs: Int = 10000): String {
        val pwFile = if (change) backend.changePasswordFile else backend.passwordFile
        val pw = readPassword(pwFile)
        val msg = (command + "#pw=" + pw + (if (args.isNotEmpty()) "&$args" else "")).toByteArray(Charsets.UTF_8)

        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs)
            s.soTimeout = timeoutMs
            // header and message in one write: the backend expects the whole packet in one read
            val packet = ByteBuffer.allocate(4 + msg.size).order(ByteOrder.LITTLE_ENDIAN).putInt(msg.size).put(msg).array()
            s.getOutputStream().apply {
                write(packet)
                flush()
            }
            val input = DataInputStream(s.getInputStream())
            val lenBuf = ByteArray(4)
            input.readFully(lenBuf)
            val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0 || len > MAX_RESPONSE) throw IOException("Invalid response length $len")
            val data = ByteArray(len)
            input.readFully(data)
            return String(data, Charsets.UTF_8)
        }
    }

    private fun readPassword(f: File): String {
        if (!f.exists()) throw IOException("Backend password file ${f.name} does not exist yet")
        return f.readText().trim()
    }

    fun isReady(): Boolean = try {
        status()
        true
    } catch (e: Exception) {
        Log.d("UrBackup", "backend not ready: $e")
        false
    }

    // plain STATUS waits for a status change (the tray icon uses it); STATUS DETAIL answers right away
    fun status(): JSONObject = JSONObject(request("STATUS DETAIL"))

    /** Replaces the backup folders. Paths are absolute, names are the folder names on the server. */
    fun saveBackupDirs(paths: List<String>): Boolean {
        val sb = StringBuilder("all_virtual_clients=1&enable_client_paths_use=1")
        paths.forEachIndexed { i, path ->
            val name = File(path).name.ifEmpty { "root" }
            sb.append("&dir_").append(i).append('=').append(escapeParam(path))
            // same default flags urbackupclientctl add-backupdir uses
            sb.append("&dir_").append(i).append("_name=")
                .append(escapeParam("$name/follow_symlinks,symlinks_optional,share_hashes"))
            sb.append("&dir_").append(i).append("_group=0")
        }
        return checkOk("SAVE BACKUP DIRS", request("SAVE BACKUP DIRS", sb.toString(), change = true))
    }

    /** Settings in the backend's settings.cfg format, e.g. "internet_server" to "host". */
    fun updateSettings(settings: Map<String, String>): Boolean {
        val data = settings.entries.joinToString("") { "${it.key}=${it.value}\n" }
        val r = request("UPDATE SETTINGS " + escapeMessage(data), change = true)
        // NOSERVER: saved, but no server was connected to be told about it (normal on the first start)
        return r == "NOSERVER" || checkOk("UPDATE SETTINGS", r)
    }

    private fun checkOk(command: String, response: String): Boolean {
        if (response != "OK") Log.w("UrBackup", "$command: unexpected response \"$response\"")
        return response == "OK"
    }

    /** Returns the backend's answer: OK, RUNNING or NO SERVER. */
    fun startBackup(full: Boolean): String =
        request(if (full) "START BACKUP FULL" else "START BACKUP INCR")

    companion object {
        const val PORT = 35623
        private const val MAX_RESPONSE = 32 * 1024 * 1024

        /** Percent-encodes everything except unreserved characters (the backend decodes %XX). */
        fun escapeParam(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code
                    || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
                ) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(String.format("%02X", c))
                }
            }
            return sb.toString()
        }

        /** The backend splits commands at '#', so '#' becomes "$r" and '$' becomes "$$". */
        fun escapeMessage(s: String): String = s.replace("$", "$$").replace("#", "\$r")
    }
}
