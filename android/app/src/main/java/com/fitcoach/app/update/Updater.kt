package com.fitcoach.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.fitcoach.app.engine.Outbound
import com.fitcoach.app.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases (public repo, no token). CI publishes a signed APK per push to master;
 * the app checks the latest release, notifies once per new version, and installs it with PackageInstaller.
 */
object Updater {
    private const val REPO = "RitikSisodiy/fitcoach"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val NOTIFICATION_ID = 2001

    data class Release(val version: String, val apkUrl: String, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun currentVersion(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** True if [remote] is a higher dotted version than [current] ("1.1.12" > "1.1.9"). */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val d = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return Release(o.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"), o.optString("body").take(500))
    }

    /** A newer release found by an earlier check, if any. */
    fun available(ctx: Context): Release? {
        val p = prefs(ctx)
        val v = p.getString("version", null) ?: return null
        if (!isNewer(v, currentVersion(ctx))) return null
        return Release(v, p.getString("url", "")!!, p.getString("notes", "")!!)
    }

    /** Checks GitHub (at most every 6 h unless [force]) and notifies once per new version. Never throws. */
    suspend fun check(ctx: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked_at", 0L) < CHECK_EVERY_MS) return@withContext available(ctx)
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            conn.disconnect()
            p.edit().putLong("checked_at", now).apply()
            val rel = body?.let(::parseRelease) ?: return@withContext available(ctx)
            p.edit().putString("version", rel.version).putString("url", rel.apkUrl).putString("notes", rel.notes).apply()
            if (isNewer(rel.version, currentVersion(ctx)) && p.getString("notified", null) != rel.version) {
                p.edit().putString("notified", rel.version).apply()
                Notifier.show(ctx, Outbound("FitCoach ${rel.version} is available. Open the app and tap Update."), quiet = false, id = NOTIFICATION_ID)
            }
            available(ctx)
        } catch (e: Exception) {
            Log.w("FitCoach", "update check failed", e)
            available(ctx)
        }
    }

    /** Android asks once per app for "Install unknown apps"; returns false and opens that screen if not allowed yet. */
    fun ensureInstallAllowed(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    /** Downloads the APK and hands it to the system installer (the user confirms once). Call from the foreground. */
    suspend fun downloadAndInstall(ctx: Context, rel: Release, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("fitcoach.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }
}

/** Receives the installer status; shows the system confirmation dialog when the user needs to approve. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts on the new version
            else -> Log.w("FitCoach", "update install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
