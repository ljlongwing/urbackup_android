package org.urbackup.client.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows. Updated by [BackupService] while a session runs. */
data class SessionState(
    val running: Boolean = false,
    val phase: String = "",
    val connected: Boolean = false,
    val serverStatus: String = "",
    val action: String = "",
    val percentDone: Int = -1,
    val stayConnectedUntil: Long = 0,
)

object BackupState {
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    fun update(f: (SessionState) -> SessionState) = _session.update(f)

    private const val PREFS = "state"

    /** Persistent result of the last session, shown when no session runs. */
    fun setLastResult(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_result", message)
            .putLong("last_result_time", System.currentTimeMillis())
            .apply()
    }

    /** A problem that needs the user's (or the server admin's) attention. Empty clears it. */
    fun setWarning(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("warning", message).apply()
        _warning.value = message
    }

    private val _warning = MutableStateFlow<String?>(null)

    fun warning(context: Context): StateFlow<String?> {
        if (_warning.value == null) {
            _warning.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("warning", "")
        }
        return _warning.asStateFlow()
    }

    fun lastResult(context: Context): Pair<String, Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Pair(p.getString("last_result", "")!!, p.getLong("last_result_time", 0))
    }
}
