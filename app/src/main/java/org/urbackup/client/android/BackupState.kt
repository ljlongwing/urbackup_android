package org.urbackup.client.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
    /** No session runs. */
    IDLE,
    STARTING,
    /** The backend tries to reach the server. */
    CONNECTING,
    /** Connected, but the server has not picked up the phone yet. */
    WAITING_FOR_SERVER,
    /** The server knows the phone; it starts the backups that are due. */
    CONNECTED,
    BACKING_UP,
}

/** What the UI shows while a session runs. Updated by [BackupService]. */
data class SessionState(
    val phase: Phase = Phase.IDLE,
    val serverAddress: String = "",
    val serverStatus: String = "",
    /** FULL or INCR, from the backend. */
    val action: String = "",
    val percentDone: Int = -1,
    val doneBytes: Long = -1,
    val totalBytes: Long = -1,
    /** Bytes per millisecond. */
    val speedBpms: Double = 0.0,
    val etaMs: Long = -1,
    val stayConnectedUntil: Long = 0,
    /** A backup ran in this session and is over; the session only waits a bit before it ends. */
    val backupDone: Boolean = false,
    val backupFailed: Boolean = false,
) {
    val running: Boolean get() = phase != Phase.IDLE
}

/** Result of the last session and the last successful backup, kept across restarts. */
data class History(
    val lastResult: String = "",
    val lastResultTime: Long = 0,
    val lastResultOk: Boolean = true,
    /** Time of the last successful backup (ms), as the backend reports it. */
    val lastBackupTime: Long = 0,
    /** The folders that were configured when that backup ran. */
    val foldersAtLastBackup: Set<String> = emptySet(),
    val warning: String = "",
)

object BackupState {
    private const val PREFS = "state"

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    fun update(f: (SessionState) -> SessionState) = _session.update(f)

    private var _history: MutableStateFlow<History>? = null

    fun history(context: Context): StateFlow<History> {
        val h = _history ?: MutableStateFlow(load(context)).also { _history = it }
        return h.asStateFlow()
    }

    private fun load(context: Context): History {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return History(
            lastResult = p.getString("last_result", "")!!,
            lastResultTime = p.getLong("last_result_time", 0),
            lastResultOk = p.getBoolean("last_result_ok", true),
            lastBackupTime = p.getLong("last_backup_time", 0),
            foldersAtLastBackup = p.getStringSet("folders_at_last_backup", emptySet())!!,
            warning = p.getString("warning", "")!!,
        )
    }

    private fun save(context: Context, f: (History) -> History) {
        history(context)
        val h = _history!!
        h.update(f)
        val v = h.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_result", v.lastResult)
            .putLong("last_result_time", v.lastResultTime)
            .putBoolean("last_result_ok", v.lastResultOk)
            .putLong("last_backup_time", v.lastBackupTime)
            .putStringSet("folders_at_last_backup", v.foldersAtLastBackup)
            .putString("warning", v.warning)
            .apply()
    }

    fun setLastResult(context: Context, message: String, ok: Boolean) =
        save(context) { it.copy(lastResult = message, lastResultTime = System.currentTimeMillis(), lastResultOk = ok) }

    /** A backup finished successfully at [time] with these folders configured. */
    fun setLastBackup(context: Context, time: Long, folders: Set<String>) =
        save(context) { it.copy(lastBackupTime = time, foldersAtLastBackup = folders) }

    /** A problem that needs the user's (or the server admin's) attention. Empty clears it. */
    fun setWarning(context: Context, message: String) = save(context) { it.copy(warning = message) }
}
