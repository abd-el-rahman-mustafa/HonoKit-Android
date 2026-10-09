package com.aamus.powerpdf.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aamus.powerpdf.BuildConfig
import com.aamus.powerpdf.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The app's only connection (decisions §36): asks GitHub whether a newer HonoKit for Android exists,
 * and, when the person taps Update, downloads the APK and hands it to Android's installer, which asks
 * them to confirm. Sends nothing but the request itself. Automatic checks run at most once a day and
 * can be turned off in Settings.
 */
object Updates {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: UpdateFeed.Release) : State
        data class Downloading(val release: UpdateFeed.Release, val percent: Int) : State
        /** Android must allow HonoKit to install apps first (Settings › Install unknown apps). */
        data class NeedsPermission(val release: UpdateFeed.Release, val apk: File) : State
        data class Installing(val release: UpdateFeed.Release) : State
        data class Failed(val message: String, val release: UpdateFeed.Release? = null) : State
    }

    private const val DAY_MS = 20L * 60 * 60 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var job: Job? = null

    private val state = MutableStateFlow<State>(State.Idle)
    val flow: StateFlow<State> = state.asStateFlow()

    /** The update dialog is open (found by an automatic check, or opened from Settings). */
    val dialog = MutableStateFlow(false)

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** At start: once a day at most, release builds only, when the person has not turned it off. */
    fun autoCheck(enabled: Boolean) {
        if (!enabled || BuildConfig.DEBUG) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < DAY_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            state.value = State.Checking
            state.value = try {
                val json = withContext(Dispatchers.IO) { get(UpdateFeed.feed) }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                val newest = UpdateFeed.newest(json, allowHttp = UpdateFeed.rehearsal)
                if (newest != null && UpdateFeed.isNewer(newest.version, BuildConfig.VERSION_NAME)) {
                    // An automatic check opens the dialog once per version; Later is remembered.
                    if (manual || prefs.getString("later", null) != newest.version) dialog.value = true
                    State.Available(newest)
                } else State.UpToDate
            } catch (e: Exception) {
                if (manual) State.Failed(app.getString(R.string.update_check_failed)) else State.Idle
            }
        }
    }

    /** "Later" on the dialog: an automatic check does not open it again for this version. */
    fun later() {
        (state.value as? State.Available)?.let { prefs.edit().putString("later", it.release.version).apply() }
        dialog.value = false
    }

    fun download(release: UpdateFeed.Release) {
        if (job?.isActive == true && state.value !is State.Available) return
        job = scope.launch { downloadNow(release) }
    }

    /**
     * Try again after a failure. The release is asked for afresh first: a damaged or replaced file
     * would otherwise be compared with the same stale details on every try (found in the rehearsal).
     */
    fun retry() {
        val failed = state.value as? State.Failed ?: return
        val old = failed.release ?: return check(manual = true)
        if (job?.isActive == true) return
        job = scope.launch {
            state.value = State.Downloading(old, 0)
            val fresh = try {
                withContext(Dispatchers.IO) { UpdateFeed.newest(get(UpdateFeed.feed), allowHttp = UpdateFeed.rehearsal) }
            } catch (e: Exception) {
                state.value = State.Failed(app.getString(R.string.update_check_failed), old)
                return@launch
            }
            if (fresh == null || !UpdateFeed.isNewer(fresh.version, BuildConfig.VERSION_NAME)) state.value = State.UpToDate
            else downloadNow(fresh)
        }
    }

    private suspend fun downloadNow(release: UpdateFeed.Release) {
        state.value = State.Downloading(release, 0)
        state.value = try {
            val apk = withContext(Dispatchers.IO) { fetch(release) }
            installOrAsk(release, apk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            State.Available(release)
        } catch (e: Exception) {
            State.Failed(e.message ?: app.getString(R.string.update_download_failed), release)
        }
    }

    fun cancel() {
        val s = state.value
        job?.cancel()
        if (s is State.Downloading) state.value = State.Available(s.release)
    }

    /** After the person returns from "Install unknown apps", or taps Install again. */
    fun install() {
        val s = state.value as? State.NeedsPermission ?: return
        state.value = installOrAsk(s.release, s.apk)
    }

    private fun installOrAsk(release: UpdateFeed.Release, apk: File): State {
        if (!app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return State.NeedsPermission(release, apk)
        }
        return try {
            commit(apk)
            State.Installing(release)
        } catch (e: Exception) {
            State.Failed(e.message ?: app.getString(R.string.update_install_failed), release)
        }
    }

    /** What Android's installer reported (InstallReceiver). Success replaces the app, so it is never seen here. */
    internal fun installResult(status: Int, message: String?) {
        val release = (state.value as? State.Installing)?.release
        state.value = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { State.Available(it) } ?: State.Idle
            PackageInstaller.STATUS_SUCCESS -> State.UpToDate
            else -> State.Failed(app.getString(R.string.update_install_failed) + (message?.let { "\n$it" } ?: ""), release)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HonoKit-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
        }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads into the app's cache, checks GitHub's SHA-256 and that the APK is this app, newer. */
    private suspend fun fetch(release: UpdateFeed.Release): File {
        val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "HonoKit-${release.version}.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        val c = open(release.apkUrl)
        try {
            if (c.responseCode != 200) throw IllegalStateException(app.getString(R.string.update_download_failed) + " (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        kotlin.coroutines.coroutineContext.ensureActive()
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                val s = state.value
                                if (s is State.Downloading && s.percent != p) state.value = s.copy(percent = p)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && hex != release.sha256) {
            file.delete()
            throw IllegalStateException(app.getString(R.string.update_corrupt))
        }
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
        val current = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val newer = info != null && (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode > current.longVersionCode else info.versionCode > current.versionCode)
        if (info?.packageName != app.packageName || !newer) {
            file.delete()
            throw IllegalStateException(app.getString(R.string.update_not_this_app))
        }
        return file
    }

    /** Hands the APK to Android's installer; it asks the person to confirm (InstallReceiver starts that screen). */
    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }
}
