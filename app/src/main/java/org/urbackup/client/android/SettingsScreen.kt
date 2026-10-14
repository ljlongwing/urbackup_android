package org.urbackup.client.android

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { AppConfig.load(context) }
    var host by remember { mutableStateOf(saved.serverHost) }
    var port by remember { mutableStateOf(saved.serverPort.toString()) }
    var key by remember { mutableStateOf(saved.authKey) }
    var name by remember {
        mutableStateOf(saved.clientName.ifEmpty {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.replace(Regex("[^A-Za-z0-9_.-]"), "-") ?: "android"
        })
    }
    var folders by remember { mutableStateOf(saved.folders) }
    var showPicker by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(saved.wifiOnly) }
    var chargingOnly by remember { mutableStateOf(saved.chargingOnly) }
    var interval by remember { mutableIntStateOf(saved.intervalHours) }
    var message by remember { mutableStateOf("") }

    if (showPicker) {
        FolderPicker(
            initial = folders,
            onDone = {
                folders = it
                showPicker = false
            },
            onCancel = { showPicker = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Folders to back up", style = MaterialTheme.typography.titleMedium)
                    if (folders.isEmpty()) {
                        Text("No folders yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    for (f in folders) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.substringAfterLast('/'))
                                if (f.contains('/')) {
                                    Text(f, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = { folders = folders - f }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $f")
                            }
                        }
                    }
                    OutlinedButton(onClick = { showPicker = true }) { Text("Browse…") }
                    Text(
                        "Photos from the camera are in DCIM, screenshots and images from other apps in Pictures.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(
                        "The server decides whether a backup is due (its backup intervals and windows apply).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
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
                    AppConfig.save(context, c)
                    Scheduling.apply(context)
                    if (c.isComplete) onBack()
                    else message = "Saved. Server address, client name, key and at least one folder are needed."
                }
            ) { Text("Save") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
