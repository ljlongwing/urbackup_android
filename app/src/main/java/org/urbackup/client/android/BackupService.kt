package org.urbackup.client.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * A backup session: runs the backend while the phone is connected to the server.
 *
 * The server decides whether a backup is due as soon as the client connects, so a scheduled
 * session waits a short time for the server to start one, waits for it to finish and then stops.
 * "Back up now" asks the backend for an incremental backup instead. "Stay connected" keeps the
 * session open for a while, e.g. so a restore started in the server's web interface can reach the phone.
 */
class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var process: Process? = null

    @Volatile private var backupRequested = false
    @Volatile private var stayConnectedUntil = 0L
    @Volatile private var stopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SESSION

        if (action == ACTION_STOP) {
            stopRequested = true
            if (sessionJob == null) stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            BackupState.setLastResult(this, "Could not start: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_BACKUP_NOW -> backupRequested = true
            ACTION_STAY_CONNECTED -> {
                stayConnectedUntil = System.currentTimeMillis() + STAY_CONNECTED_MS
                BackupState.update { it.copy(stayConnectedUntil = stayConnectedUntil) }
            }
        }

        if (sessionJob == null) {
            stopRequested = false
            sessionJob = scope.launch { runSession() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession() {
        val backend = Backend(this)
        val commands = BackendCommands(backend)
        var result = "Session ended"
        BackupState.update { SessionState(running = true, phase = "Starting", stayConnectedUntil = stayConnectedUntil) }
        try {
            val p = backend.start()
            process = p
            if (!waitFor(30_000) { commands.isReady() }) {
                result = "The backup service did not start"
                return
            }

            if (!syncConfig(commands)) {
                result = "Could not configure the backup service"
                return
            }

            val start = System.currentTimeMillis()
            var connectedSince = 0L
            var disconnectedSince = start
            var lastActivity = 0L
            var sawBackup = false
            var backupFailed = false
            var requestAccepted = false
            var addressFallbackSent = false
            var knownFinished: Set<Long>? = null

            while (!stopRequested) {
                if (!p.isAlive) {
                    result = "The backup service stopped unexpectedly (exit code ${p.exitValue()})"
                    break
                }
                val now = System.currentTimeMillis()
                val st = try {
                    commands.status()
                } catch (e: Exception) {
                    Log.w(TAG, "status failed", e)
                    null
                }

                if (st != null) {
                    // internet_connected stays true while the backend fails to reconnect (e.g. to a new
                    // address), internet_status says what is going on right now
                    val connected = st.optBoolean("internet_connected") && st.optString("internet_status") == "connected"
                    if (connected) {
                        if (connectedSince == 0L) {
                            connectedSince = now
                            // connected with the address the server sends: the server's setting works
                            if (backend.setting("internet_server") == backend.setting("internet_server_def")) {
                                BackupState.setWarning(this, "")
                            }
                        }
                        disconnectedSince = 0L
                    } else {
                        connectedSince = 0L
                        if (disconnectedSince == 0L) disconnectedSince = now
                    }

                    // The server sends its own address to the client, which then uses it instead of the one
                    // entered in the app. If that address does not work from here (e.g. a LAN address while
                    // the phone is outside), go back to the app's address for this session.
                    if (!connected && !addressFallbackSent && now - disconnectedSince > ADDRESS_FALLBACK_MS) {
                        addressFallbackSent = true
                        val config = AppConfig.load(this)
                        val host = backend.setting("internet_server")
                        val port = backend.setting("internet_server_port")
                        if (host != config.serverHost || port != config.serverPort.toString()) {
                            Log.w(TAG, "cannot connect to $host:$port (set by the server), trying ${config.serverHost}:${config.serverPort}")
                            BackupState.setWarning(
                                this,
                                "The server tells this phone to connect to $host, which does not work from here. " +
                                    "UrBackup went back to ${config.serverHost} for now, but the server will send its address " +
                                    "again. In the server's settings (Internet), enter an address the phone can reach. " +
                                    "Several addresses can be separated by \";\"."
                            )
                            commands.updateSettings(
                                mapOf(
                                    "internet_server" to config.serverHost,
                                    "internet_server_port" to config.serverPort.toString(),
                                )
                            )
                        }
                    }

                    val procs = st.optJSONArray("running_processes")
                    val proc = if (procs != null && procs.length() > 0) procs.getJSONObject(0) else null
                    if (proc != null) {
                        sawBackup = true
                        lastActivity = now
                    }

                    // a small backup can be over between two polls; it then shows up as finished
                    val finished = st.optJSONArray("finished_processes")
                    val finishedNow = HashMap<Long, Boolean>()
                    if (finished != null) {
                        for (i in 0 until finished.length()) {
                            val f = finished.getJSONObject(i)
                            finishedNow[f.optLong("process_id")] = f.optBoolean("success")
                        }
                    }
                    val known = knownFinished
                    if (known != null) {
                        for ((id, success) in finishedNow) {
                            if (id !in known) {
                                sawBackup = true
                                lastActivity = now
                                if (!success) backupFailed = true
                            }
                        }
                    }
                    knownFinished = finishedNow.keys

                    if (backupRequested && connected) {
                        val r = try { commands.startBackup(full = false) } catch (e: Exception) { "error: ${e.message}" }
                        Log.i(TAG, "start backup: $r")
                        if (r == "OK" || r == "RUNNING") {
                            backupRequested = false
                            requestAccepted = true
                            lastActivity = now
                        }
                    }

                    val phase = when {
                        proc != null -> "Backing up"
                        !connected -> "Connecting to server"
                        now < stayConnectedUntil -> "Connected (staying connected)"
                        else -> "Connected"
                    }
                    BackupState.update {
                        it.copy(
                            phase = phase,
                            connected = connected,
                            serverStatus = st.optString("internet_status"),
                            action = proc?.optString("action") ?: "",
                            percentDone = proc?.optInt("percent_done", -1) ?: -1,
                            stayConnectedUntil = stayConnectedUntil,
                        )
                    }
                    updateNotification(phase + if (proc != null && proc.optInt("percent_done", -1) >= 0) " (${proc.optInt("percent_done")}%)" else "")

                    val busy = proc != null || backupRequested || now < stayConnectedUntil
                    if (!busy) {
                        if (connectedSince != 0L && now - connectedSince > IDLE_AFTER_CONNECT_MS
                            && (lastActivity == 0L || now - lastActivity > IDLE_AFTER_BACKUP_MS)
                        ) {
                            result = when {
                                backupFailed -> "The backup failed. The server's log has the details."
                                sawBackup -> "Backup finished"
                                requestAccepted -> "The server did not start the backup"
                                else -> "Connected, no backup was due"
                            }
                            break
                        }
                        if (!connected && now - disconnectedSince > CONNECT_TIMEOUT_MS) {
                            result = "Could not connect to the server: ${st.optString("internet_status")}"
                            break
                        }
                    }
                }
                delay(POLL_MS)
            }
            if (stopRequested) result = "Stopped"
        } catch (e: Exception) {
            Log.e(TAG, "session failed", e)
            result = "Error: ${e.message}"
        } finally {
            stopBackend()
            BackupState.setLastResult(this, result)
            BackupState.update { SessionState() }
            sessionJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Sends changed folders and server settings to the backend. Folders first, see [Backend.prepare]. */
    private fun syncConfig(commands: BackendCommands): Boolean {
        val config = AppConfig.load(this)
        if (AppConfig.isFoldersDirty(this)) {
            val root = AppConfig.storageRoot()
            if (!commands.saveBackupDirs(config.folders.map { if (it.startsWith("/")) it else "$root/$it" })) return false
            AppConfig.clearFoldersDirty(this)
        }
        if (AppConfig.isSettingsDirty(this)) {
            val ok = commands.updateSettings(
                mapOf(
                    "internet_mode_enabled" to "true",
                    "internet_server" to config.serverHost,
                    "internet_server_port" to config.serverPort.toString(),
                    "internet_authkey" to config.authKey,
                    "computername" to config.clientName,
                )
            )
            if (!ok) return false
            AppConfig.clearSettingsDirty(this)
        }
        return true
    }

    private fun stopBackend() {
        val p = process ?: return
        process = null
        p.destroy() // SIGTERM, the backend shuts down cleanly
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly()
        }
    }

    private suspend fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            delay(500)
        }
        return false
    }

    // Android 15+ stops dataSync services after 6 hours per day
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached")
        stopRequested = true
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, UrBackupApp.CHANNEL_SESSION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopBackend()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "UrBackup"
        private const val NOTIFICATION_ID = 1

        const val ACTION_SESSION = "org.urbackup.client.android.SESSION"
        const val ACTION_BACKUP_NOW = "org.urbackup.client.android.BACKUP_NOW"
        const val ACTION_STAY_CONNECTED = "org.urbackup.client.android.STAY_CONNECTED"
        const val ACTION_STOP = "org.urbackup.client.android.STOP"

        private const val POLL_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 2 * 60 * 1000L
        /** Not connected after this long: try the address from the app instead of the server's. */
        private const val ADDRESS_FALLBACK_MS = 40 * 1000L
        /** How long to wait for the server to start a backup after connecting. */
        private const val IDLE_AFTER_CONNECT_MS = 2 * 60 * 1000L
        /** The server may start another backup (e.g. a different group) right after one finished. */
        private const val IDLE_AFTER_BACKUP_MS = 60 * 1000L
        private const val STAY_CONNECTED_MS = 15 * 60 * 1000L

        fun start(context: Context, action: String) {
            context.startForegroundService(Intent(context, BackupService::class.java).setAction(action))
        }

        /** Only from the UI: a plain startService, because ACTION_STOP does not call startForeground. */
        fun stop(context: Context) {
            context.startService(Intent(context, BackupService::class.java).setAction(ACTION_STOP))
        }
    }
}
