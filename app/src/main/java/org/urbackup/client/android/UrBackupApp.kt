package org.urbackup.client.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class UrBackupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Backup sessions", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
    }
}
