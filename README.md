# UrBackup client for Android

An Android app that backs up folders on the phone (photos, documents, downloads, …) to an
[UrBackup](https://www.urbackup.org) server.

Status: early development. It works against a 2.6.x server in internet mode, but it is not
ready for everyday use yet.

## How it works

- The app runs the regular UrBackup client backend, built with the Android NDK. The backend is
  packaged as `lib/<abi>/libubbackend.so` and started as a separate process from the app's
  native library directory, which is the only place an app is allowed to execute files from.
- "All files access" (Android 11+) lets the backend read the shared storage
  (`/storage/emulated/0/...`) with normal file paths.
- Internet mode only. The phone connects to the server; the server never has to reach the phone.
- A periodic job (WorkManager; Wi-Fi and charging conditions are optional) starts a backup
  session as a foreground service. The backend connects, the server decides whether a backup is
  due, and the session ends shortly after the server is done. "Back up now" asks for an
  incremental backup right away.
- File backups only. Android has no snapshots, so files are read as they are.
- Restores go through the server's web interface. "Stay connected for 15 minutes" keeps the phone
  online so a restore can reach it.
- The app talks to the backend over the backend's local command interface (127.0.0.1:35623),
  the same one `urbackupclientctl` and the desktop client use.

The server sends its "Internet server name/IP" setting to every client, and the backend then
uses it instead of the address entered in the app. If phones are sometimes inside and sometimes
outside the LAN, enter all addresses there, separated by ";" (e.g. `backup.example.com;192.168.1.10`).
The app shows a warning if the address it gets from the server does not work.

Scheduled backups need the app to be exempt from battery optimization: since Android 12 an app
may not start a foreground service from the background otherwise. The app asks for it.

## Setting it up

1. On the server, add an internet client (Status → "Add new client" → internet client) and make
   sure internet mode is set up under Settings → Internet.
2. In the app, enter the server address, port, client name and key, pick the folders and save.
3. Grant the permissions the app asks for.

## Building

The backend comes from [urbackup_backend](https://github.com/uroni/urbackup_backend) (2.6.x branch)
and is built separately:

```sh
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/29.0.14206865
export VCPKG_ROOT=/path/to/vcpkg
scripts/build_backend.sh /path/to/urbackup_backend arm64-v8a x86_64
```

This builds the backend and its dependencies (via vcpkg) for API level 30 and puts the stripped
binaries into `app/src/main/jniLibs/`. Until uroni/urbackup_backend#177 is merged, the backend needs
that change, otherwise every file fails to open with "Permission denied".

Then build the app:

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

## License

GNU Affero General Public License v3 or later, like the rest of UrBackup. See [LICENSE](LICENSE).
