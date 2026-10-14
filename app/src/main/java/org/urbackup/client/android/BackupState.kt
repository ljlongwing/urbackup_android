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

    fun lastResult(context: Context): Pair<String, Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Pair(p.getString("last_result", "")!!, p.getLong("last_result_time", 0))
    }
}
