package org.urbackup.client.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    // bumped in onResume so permission states are read again after returning from the settings
    private var resumeCount by mutableIntStateOf(0)
    // bumped on save so the status card sees the new configuration
    private var configVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(resumeCount)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    @Composable
    private fun MainScreen(resumeCount: Int) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("UrBackup", style = MaterialTheme.typography.headlineMedium)
            PermissionsCard(resumeCount)
            StatusCard(resumeCount)
            SettingsCard()
        }
    }

    @Composable
    private fun PermissionsCard(resumeCount: Int) {
        val allFiles = remember(resumeCount) { Environment.isExternalStorageManager() }
        val batteryExempt = remember(resumeCount) {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }
        val notifications = remember(resumeCount) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        if (allFiles && batteryExempt && notifications) return

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                if (!allFiles) {
                    Text("UrBackup needs access to all files to back up your folders.")
                    Button(onClick = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    }) { Text("Allow access to all files") }
                }
                if (!batteryExempt) {
                    Text("Scheduled backups can only start in the background if UrBackup is not battery optimized.")
                    Button(onClick = { requestBatteryExemption() }) { Text("Allow background backups") }
                }
                if (!notifications) {
                    Text("A notification shows while a backup runs.")
                    Button(onClick = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }) {
                        Text("Allow notifications")
                    }
                }
            }
        }
    }

    // Backup apps are an accepted use of this request; it only opens the system dialog
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    @Composable
    private fun StatusCard(resumeCount: Int) {
        val session by BackupState.session.collectAsStateWithLifecycle()
        val config = remember(resumeCount, configVersion, session.running) { AppConfig.load(this) }
        val last = remember(resumeCount, session.running) { BackupState.lastResult(this) }
        val warning by BackupState.warning(this).collectAsStateWithLifecycle()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                if (!warning.isNullOrEmpty()) {
                    Text(warning!!, color = MaterialTheme.colorScheme.error)
                }
                if (session.running) {
                    Text(session.phase)
                    if (session.percentDone >= 0) {
                        LinearProgressIndicator(
                            progress = { session.percentDone / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!session.connected && session.serverStatus.isNotEmpty()) {
                        Text(session.serverStatus, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (last.first.isNotEmpty()) {
                    Text(last.first)
                    Text(
                        DateUtils.getRelativeTimeSpanString(last.second).toString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(if (config.isComplete) "No backup session yet" else "Set up the server and folders below")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = config.isComplete,
                        onClick = { BackupService.start(this@MainActivity, BackupService.ACTION_BACKUP_NOW) }
                    ) { Text("Back up now") }
                    if (session.running) {
                        OutlinedButton(onClick = { BackupService.stop(this@MainActivity) }) { Text("Stop") }
                    }
                }
                OutlinedButton(
                    enabled = config.isComplete,
                    onClick = { BackupService.start(this@MainActivity, BackupService.ACTION_STAY_CONNECTED) }
                ) { Text("Stay connected for 15 minutes") }
                Text(
                    "Restores are started in the server's web interface. The phone has to be connected for that.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun SettingsCard() {
        val saved = remember { AppConfig.load(this) }
        var host by remember { mutableStateOf(saved.serverHost) }
        var port by remember { mutableStateOf(saved.serverPort.toString()) }
        var key by remember { mutableStateOf(saved.authKey) }
        var name by remember { mutableStateOf(saved.clientName.ifEmpty { defaultClientName() }) }
        var folders by remember { mutableStateOf(saved.folders) }
        var customFolder by remember { mutableStateOf("") }
        var wifiOnly by remember { mutableStateOf(saved.wifiOnly) }
        var chargingOnly by remember { mutableStateOf(saved.chargingOnly) }
        var interval by remember { mutableIntStateOf(saved.intervalHours) }
        var message by remember { mutableStateOf("") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(host, { host = it }, label = { Text("Server address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Client name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("Client key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text(
                    "Add the client in the server's web interface (Status, \"Add new client\", internet client) " +
                        "and copy its name and key here.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.width(8.dp))
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                val presets = AppConfig.PRESET_FOLDERS
                for (f in presets + folders.filter { it !in presets }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(f in folders, role = Role.Checkbox) { checked ->
                                folders = if (checked) folders + f else folders - f
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(f in folders, null)
                        Text(f)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(customFolder, { customFolder = it }, label = { Text("Other folder") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val f = customFolder.trim().trimEnd('/')
                        if (f.isNotEmpty() && f !in folders) folders = folders + f
                        customFolder = ""
                    }) { Text("Add") }
                }
                Text(
                    "Other folders are relative to the phone's storage (e.g. WhatsApp/Media) or an absolute path.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.width(8.dp))
                Text("Schedule", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().toggleable(wifiOnly, role = Role.Switch) { wifiOnly = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only on Wi-Fi", Modifier.weight(1f))
                    Switch(wifiOnly, null)
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(chargingOnly, role = Role.Switch) { chargingOnly = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only while charging", Modifier.weight(1f))
                    Switch(chargingOnly, null)
                }
                Text("Check with the server every")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in listOf(1, 3, 6, 12, 24)) {
                        FilterChip(interval == h, { interval = h }, label = { Text("${h}h") })
                    }
                }

                Button(onClick = {
                    val c = AppConfig(
                        serverHost = host,
                        serverPort = port.toIntOrNull() ?: 55415,
                        authKey = key,
                        clientName = name,
                        folders = folders,
                        wifiOnly = wifiOnly,
                        chargingOnly = chargingOnly,
                        intervalHours = interval,
                    )
                    AppConfig.save(this@MainActivity, c)
                    Scheduling.apply(this@MainActivity)
                    configVersion++
                    message = if (c.isComplete) "Saved" else "Saved. Server, client name, key and at least one folder are needed."
                }) { Text("Save") }
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun defaultClientName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.replace(Regex("[^A-Za-z0-9_.-]"), "-") ?: "android"
}
