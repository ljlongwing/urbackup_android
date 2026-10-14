package org.urbackup.client.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(resumeCount: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val session by BackupState.session.collectAsStateWithLifecycle()
    val history by BackupState.history(context).collectAsStateWithLifecycle()
    val nextRun by Scheduling.nextRun(context).collectAsStateWithLifecycle(initialValue = null)
    val config = remember(resumeCount, session.phase) { AppConfig.load(context) }

    // ticks once a minute so "2 minutes ago" and the restore countdown stay current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (session.stayConnectedUntil > now) 1000 else 30_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UrBackup") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProblemCards(resumeCount, history.warning)
            if (!config.isComplete) {
                SetupCard(onOpenSettings)
            } else {
                StatusCard(session, history, nextRun, now)
                FoldersCard(config, session, history, resumeCount, onOpenSettings)
                RestoreCard(session, now)
            }
        }
    }
}

/** Green for "backed up"; a lighter one on dark backgrounds. */
@Composable
private fun okColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/** Missing permissions and warnings, each with what to do about it. */
@Composable
private fun ProblemCards(resumeCount: Int, warning: String) {
    val context = LocalContext.current
    val allFiles = remember(resumeCount) { Environment.isExternalStorageManager() }
    val batteryExempt = remember(resumeCount) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val notifications = remember(resumeCount) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    if (!allFiles) {
        ProblemCard("UrBackup needs access to all files to back up your folders.", "Allow access") {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }
    }
    if (!batteryExempt) {
        ProblemCard(
            "Scheduled backups can only start in the background if UrBackup is not battery optimized.",
            "Allow background backups"
        ) { requestBatteryExemption(context) }
    }
    if (!notifications) {
        ProblemCard("Allow notifications to see when a backup runs.", "Allow notifications") {
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
    if (warning.isNotEmpty()) {
        ProblemCard(warning, null) {}
    }
}

// Backup apps are an accepted use of this request; it only opens the system dialog
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    )
}

@Composable
private fun ProblemCard(text: String, button: String?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (button != null) {
                    Button(onClick = onClick) { Text(button) }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up UrBackup", style = MaterialTheme.typography.titleMedium)
            Text("Enter your server and choose the folders to back up.")
            Button(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

/** The big card at the top: what is happening right now, or how the last backup went. */
@Composable
private fun StatusCard(session: SessionState, history: History, nextRun: Long?, now: Long) {
    val context = LocalContext.current
    val running = session.running
    val problem = !running && history.lastResultTime > 0 && !history.lastResultOk
    val colors = when {
        running -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        problem -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        else -> CardDefaults.cardColors()
    }

    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                val done = session.phase == Phase.CONNECTED && session.backupDone
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (done) {
                        Icon(
                            if (session.backupFailed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (session.backupFailed) MaterialTheme.colorScheme.error else okColor()
                        )
                        Spacer(Modifier.width(12.dp))
                    } else if (session.phase != Phase.BACKING_UP) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        if (session.phase == Phase.BACKING_UP) "Backing up" else Format.phaseText(session),
                        style = if (session.phase == Phase.BACKING_UP) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.titleLarge
                    )
                }
                if ((session.phase == Phase.WAITING_FOR_SERVER || session.phase == Phase.CONNECTED)
                    && session.serverAddress.isNotEmpty()
                ) {
                    Text("Connected to ${session.serverAddress}", style = MaterialTheme.typography.bodyMedium)
                }
                if (session.phase == Phase.BACKING_UP) {
                    Text(Format.backupTitle(session.action), style = MaterialTheme.typography.titleSmall)
                    if (session.percentDone >= 0) {
                        LinearProgressIndicator(
                            progress = { session.percentDone / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(Format.progressLine(session))
                }
                if (session.phase == Phase.CONNECTED) {
                    Text(
                        when {
                            session.stayConnectedUntil > now -> "Waiting for restores from the server."
                            done -> "Disconnecting shortly, in case the server starts another backup first."
                            else -> "The server decides whether a backup is due."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = { BackupService.stop(context) }) {
                    Text(if (session.phase == Phase.CONNECTED && (session.backupDone || session.stayConnectedUntil > now)) "Disconnect now" else "Stop")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (problem) Icons.Filled.Warning else if (history.lastBackupTime > 0) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (problem) MaterialTheme.colorScheme.error
                        else if (history.lastBackupTime > 0) okColor() else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (history.lastBackupTime > 0) "Last backup ${Format.ago(history.lastBackupTime)}"
                        else "No backup yet",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                if (history.lastBackupTime > 0) {
                    Text(Format.dateTime(history.lastBackupTime), style = MaterialTheme.typography.bodyMedium)
                }
                // after a successful backup the headline already says it all
                if (history.lastResultTime > 0 && !(history.lastResultOk && history.lastResult == "Backup finished")) {
                    Text(
                        "Last check ${Format.ago(history.lastResultTime)}: ${history.lastResult}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (nextRun != null) {
                    Text(
                        if (nextRun <= now) "Next check: as soon as the conditions are met"
                        else "Next check: ${Format.dateTime(nextRun)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = { BackupService.start(context, BackupService.ACTION_BACKUP_NOW) }) {
                    Text("Back up now")
                }
            }
        }
    }
}

/** The folders that are backed up, what they contain and whether the last backup had them. */
@Composable
private fun FoldersCard(
    config: AppConfig,
    session: SessionState,
    history: History,
    resumeCount: Int,
    onOpenSettings: () -> Unit,
) {
    val stats = remember { mutableStateMapOf<String, FolderStats>() }
    LaunchedEffect(config.folders, resumeCount, history.lastBackupTime) {
        withContext(Dispatchers.IO) {
            for (f in config.folders) {
                stats[f] = FolderScanner.scan(AppConfig.absolutePath(f))
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("Change") }
            }
            config.folders.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val s = stats[f]
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(f.substringAfterLast('/'), fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            s == null -> "Counting files…"
                            !s.exists -> "Folder not found"
                            else -> "${Format.files(s.files)} · ${Format.bytes(s.bytes)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val (text, color) = when {
                        s != null && !s.exists -> "Not backed up: the folder does not exist" to MaterialTheme.colorScheme.error
                        session.phase == Phase.BACKING_UP -> "Backing up…" to MaterialTheme.colorScheme.primary
                        history.lastBackupTime > 0 && f in history.foldersAtLastBackup ->
                            "Backed up ${Format.ago(history.lastBackupTime)}" to okColor()
                        else -> "Not backed up yet" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

/** Restores are started on the server; the phone only has to be online for them. */
@Composable
private fun RestoreCard(session: SessionState, now: Long) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Restore files", style = MaterialTheme.typography.titleMedium)
            Text(
                "Restores are started in the server's web interface. The phone has to be connected " +
                    "while the restore runs.",
                style = MaterialTheme.typography.bodyMedium
            )
            val left = session.stayConnectedUntil - now
            if (session.running && left > 0) {
                Text(
                    "Restores are allowed for ${Format.duration(left)} more.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                OutlinedButton(onClick = { BackupService.start(context, BackupService.ACTION_STAY_CONNECTED) }) {
                    Text("Allow restores for 15 minutes")
                }
            }
        }
    }
}
