package org.urbackup.client.android

import android.content.Context
import android.os.Environment

/** User configuration. Stored in SharedPreferences, pushed to the backend at the start of a session. */
data class AppConfig(
    val serverHost: String = "",
    val serverPort: Int = 55415,
    val authKey: String = "",
    val clientName: String = "",
    val folders: List<String> = emptyList(),
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val intervalHours: Int = 6,
) {
    val isComplete: Boolean
        get() = serverHost.isNotBlank() && authKey.isNotBlank() && clientName.isNotBlank() && folders.isNotEmpty()

    companion object {
        private const val PREFS = "config"

        /** Folders offered as presets. Paths are relative to the shared storage root. */
        val PRESET_FOLDERS = listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_RECORDINGS,
        )

        fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

        /** Folders are stored relative to the storage root unless they are absolute. */
        fun absolutePath(folder: String): String = if (folder.startsWith("/")) folder else "${storageRoot()}/$folder"

        fun load(context: Context): AppConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppConfig(
                serverHost = p.getString("server_host", "")!!,
                serverPort = p.getInt("server_port", 55415),
                authKey = p.getString("auth_key", "")!!,
                clientName = p.getString("client_name", "")!!,
                folders = p.getString("folders", "")!!.split('\n').filter { it.isNotEmpty() },
                wifiOnly = p.getBoolean("wifi_only", true),
                chargingOnly = p.getBoolean("charging_only", false),
                intervalHours = p.getInt("interval_hours", 6),
            )
        }

        /**
         * Saves the configuration and marks what the backend still has to be told about.
         * The flags are cleared by [BackupService] once the backend accepted the change.
         */
        fun save(context: Context, c: AppConfig) {
            val old = load(context)
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = p.edit()
                .putString("server_host", c.serverHost.trim())
                .putInt("server_port", c.serverPort)
                .putString("auth_key", c.authKey.trim())
                .putString("client_name", c.clientName.trim())
                .putString("folders", c.folders.joinToString("\n"))
                .putBoolean("wifi_only", c.wifiOnly)
                .putBoolean("charging_only", c.chargingOnly)
                .putInt("interval_hours", c.intervalHours)
            if (old.folders != c.folders) e.putBoolean("folders_dirty", true)
            if (old.serverHost != c.serverHost || old.serverPort != c.serverPort
                || old.authKey != c.authKey || old.clientName != c.clientName
            ) {
                e.putBoolean("settings_dirty", true)
            }
            e.apply()
        }

        fun isFoldersDirty(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("folders_dirty", false)

        fun isSettingsDirty(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("settings_dirty", false)

        fun clearFoldersDirty(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("folders_dirty", false).apply()

        fun clearSettingsDirty(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("settings_dirty", false).apply()
    }
}
