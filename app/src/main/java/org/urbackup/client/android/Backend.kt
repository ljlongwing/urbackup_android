package org.urbackup.client.android

import android.content.Context
import java.io.File

/** Where the backend lives and how it is started. */
class Backend(private val context: Context) {
    val workDir: File = File(context.filesDir, "ub")
    private val dataDir = File(workDir, "urbackup/data")
    val logFile = File(workDir, "client.log")
    private val stdoutFile = File(workDir, "stdout.log")

    val passwordFile = File(workDir, "urbackup/pw.txt")
    val changePasswordFile = File(workDir, "urbackup/pw_change.txt")

    private val binary: String
        get() = context.applicationInfo.nativeLibraryDir + "/libubbackend.so"

    /**
     * The backend connects to the server as soon as it starts if settings.cfg enables internet mode.
     * On the first start it must not do that before it knows the backup folders (the server would
     * start a backup of nothing), so settings.cfg starts with internet mode off and the real settings
     * are sent with UPDATE SETTINGS after the folders were saved.
     */
    fun prepare() {
        dataDir.mkdirs()
        File(workDir, "scripts").mkdirs()
        val settings = File(dataDir, "settings.cfg")
        if (!settings.exists()) {
            settings.writeText("internet_mode_enabled=false\n")
        }
    }

    fun start(): Process {
        prepare()
        val cmd = listOf(
            binary,
            // pass the arguments through to the backend unchanged; the normal command line
            // has no option for the working directory
            "--internal",
            "--no-server",
            "--workingdir", workDir.absolutePath,
            "--script_path", File(workDir, "scripts").absolutePath,
            "--logfile", logFile.absolutePath,
            "--loglevel", "info",
            "--rotate-filesize", (5 * 1024 * 1024).toString(),
            "--rotate-numfiles", "3",
            "--internet_only_mode", "true",
            "--allow_restore", "server-confirms",
        )
        return ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(stdoutFile)
            .start()
    }
}
