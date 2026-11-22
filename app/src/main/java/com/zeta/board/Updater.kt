package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object Updater {

    /** A small patch that turns one older version into the newest one (made by GitHub Actions). */
    class Patch(val url: String, val size: Long)

    class Info(
        val code: Long,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val sha256: String = "",
        val patches: Map<Long, Patch> = emptyMap()
    )

    class Task {
        @Volatile
        var cancelled = false
    }

    // APK waiting for the "install unknown apps" permission
    var pending: File? = null

    private val main = Handler(Looper.getMainLooper())

    // PackageInfoCompat works on Android 8+ (longVersionCode alone crashes on API 26-27)
    fun currentCode(c: Context): Long = PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))

    fun currentName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "1.0"

    var dialogOpen = false

    /** True while the app's own screen is in front (set by MainActivity). Used to decide between installing right away and a notification. */
    @Volatile
    var foreground = false

    /** A finished download that is newer than the installed version and still intact, or null. */
    fun readyApk(c: Context): File? {
        val f = File(File(c.applicationContext.cacheDir, "updates"), "ZetaBoard.apk")
        if (!isValidApk(c, f)) return null
        val pi = c.packageManager.getPackageArchiveInfo(f.path, 0) ?: return null
        return if (PackageInfoCompat.getLongVersionCode(pi) > currentCode(c)) f else null
    }

    // the update dialog currently on screen (dismissed when the activity is destroyed)
    var dialog: Dialog? = null

    private fun get(url: String, accept: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("User-Agent", "ZetaBoard")
        if (accept != null) conn.setRequestProperty("Accept", accept)
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun fromJson(text: String): Info {
        val j = JSONObject(text)
        val patches = HashMap<Long, Patch>()
        val po = j.optJSONObject("patches")
        if (po != null) {
            val keys = po.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val from = k.toLongOrNull() ?: continue
                val o = po.optJSONObject(k) ?: continue
                val url = o.optString("url", "")
                if (url.isNotEmpty()) patches[from] = Patch(url, o.optLong("size", 0L))
            }
        }
        return Info(j.getLong("versionCode"), j.optString("versionName", ""), j.optString("notes", ""), j.getString("apkUrl"), j.optString("apkSha256", ""), patches)
    }

    private fun fromApi(text: String): Info {
        val j = JSONObject(text)
        val tag = j.getString("tag_name")
        val code = tag.removePrefix("v").toLongOrNull() ?: throw IllegalStateException("bad tag $tag")
        val assets = j.getJSONArray("assets")
        var apk = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
        }
        if (apk.isEmpty()) throw IllegalStateException("no apk in release")
        return Info(code, "1.0.$code", j.optString("body", ""), apk)
    }

    /** Reads the newest published release (update.json first, GitHub API as a fallback). Throws when it cannot be read. */
    private fun fetchLatest(): Info {
        val repo = BuildConfig.REPO
        if (repo.isEmpty()) throw IllegalStateException("this build has no repo set - build it with GitHub Actions")
        var latest: Info? = null
        var e1 = ""
        try {
            latest = fromJson(get("https://github.com/$repo/releases/latest/download/update.json", null))
        } catch (e: Exception) {
            e1 = "update.json: " + (e.message ?: e.javaClass.simpleName)
        }
        if (latest == null) {
            try {
                latest = fromApi(get("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json"))
            } catch (e: Exception) {
                throw IllegalStateException(e1 + " / api: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return latest!!
    }

    /** onResult(info, error): info != null when a newer version exists; error != null when the check failed. */
    fun check(c: Context, onResult: (Info?, String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var info: Info? = null
            var err: String? = null
            try {
                val l = fetchLatest()
                if (l.code > currentCode(app)) info = Info(l.code, l.name.ifBlank { "1.0." + l.code }, l.notes, l.apkUrl, l.sha256, l.patches)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            main.post { onResult(info, err) }
        }.start()
    }

    /** The newest published release, newer than the installed one or not (null if it cannot be read). Used for the "what's new" notes. */
    fun latest(c: Context, onResult: (Info?) -> Unit) {
        Thread {
            var info: Info? = null
            try {
                info = fetchLatest()
            } catch (e: Exception) {
            }
            main.post { onResult(info) }
        }.start()
    }

    /** The downloaded file must really be a Zeta Board APK, otherwise the installer would just fail. */
    private fun isValidApk(c: Context, f: File): Boolean {
        if (!f.exists() || f.length() < 100000L) return false
        val pi = c.packageManager.getPackageArchiveInfo(f.path, 0) ?: return false
        return pi.packageName == c.packageName
    }

    /** Downloads [url] into [sink]. Reports (downloadedBytes, totalBytes). Throws on any problem or when cancelled. */
    private fun httpDownload(url: String, task: Task, sink: OutputStream, report: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZetaBoard")
            val http = conn.responseCode
            if (http != 200) throw IllegalStateException("HTTP $http")
            val total = conn.contentLengthLong
            var got = 0L
            var last = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    if (task.cancelled) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    got += n
                    val now = System.currentTimeMillis()
                    if (now - last > 120) {
                        last = now
                        report(got, total)
                    }
                }
            }
            if (total > 0 && got != total) throw IllegalStateException("incomplete download")
            report(got, total)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Small update: downloads only the patch for the installed version and rebuilds the new apk from the
     * apk that is already on the phone. Returns false when no patch is available or anything goes wrong,
     * so the caller can fall back to the normal full download.
     */
    private fun tryDelta(app: Context, url: String, out: File, task: Task, report: (Long, Long) -> Unit): Boolean {
        val ai = app.applicationInfo
        if (ai.splitSourceDirs?.isNotEmpty() == true) return false
        val base = File(ai.sourceDir)
        if (!base.exists()) return false
        val latest = try {
            fetchLatest()
        } catch (e: Exception) {
            return false
        }
        if (latest.apkUrl != url || latest.sha256.isEmpty()) return false
        val patch = latest.patches[currentCode(app)] ?: return false
        if (patch.size <= 0L || patch.size > 100L * 1024 * 1024) return false
        try {
            val bos = ByteArrayOutputStream(patch.size.toInt())
            httpDownload(patch.url, task, bos, report)
            DeltaPatch.apply(base, bos.toByteArray(), out) { task.cancelled }
            if (DeltaPatch.sha256(out) != latest.sha256.lowercase(Locale.US)) throw IllegalStateException("patched apk does not match")
            if (!isValidApk(app, out)) throw IllegalStateException("patched apk is invalid")
            return true
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Crash.log("deltaUpdate", e)
            out.delete()
            return false
        }
    }

    /** onProgress(percent, downloadedBytes, totalBytes); onDone(file or null on failure/cancel). Both run on the main thread. */
    fun download(
        c: Context,
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
        onDone: (File?) -> Unit
    ): Task {
        val task = Task()
        val app = c.applicationContext
        val dir = File(app.cacheDir, "updates")
        Thread {
            val ptr = Perf.begin("update_download")
            val out = File(dir, "ZetaBoard.apk")
            var lastTotal = 0L
            var lastGot = 0L
            val report: (Long, Long) -> Unit = { got, total ->
                lastGot = got
                lastTotal = total
                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                main.post { onProgress(pct, got, total) }
            }
            try {
                dir.mkdirs()
                out.delete()
                var result = "ok"
                if (tryDelta(app, url, out, task, report)) {
                    result = "ok_delta"
                } else {
                    out.delete()
                    FileOutputStream(out).use { fos -> httpDownload(url, task, fos, report) }
                    if (!isValidApk(app, out)) throw IllegalStateException("invalid apk")
                }
                val g = lastGot
                val t = lastTotal
                Perf.end(ptr, result)
                main.post {
                    onProgress(100, g, t)
                    onDone(out)
                }
            } catch (e: Exception) {
                Perf.end(ptr, if (e is InterruptedException) "cancelled" else "error")
                if (e !is InterruptedException) Crash.log("updateDownload", e)
                out.delete()
                main.post { onDone(null) }
            }
        }.start()
        return task
    }

    fun install(a: Activity, f: File) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            pending = f
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
