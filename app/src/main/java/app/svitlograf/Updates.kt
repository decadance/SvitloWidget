package app.svitlograf

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updates for the GitHub build: the Mac mini serves `latest.json` and the APKs next to it
 * (same scheme as Sleep Hub). The Play build has no update URL, so all of this is off there.
 */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val notes: Map<String, String>,
) {
    fun notes(lang: String): String? = notes[lang] ?: notes["en"]

    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName).put("file", file)
        .put("bytes", bytes).put("sha256", sha256).put("notes", JSONObject(notes))
        .toString()

    companion object {
        fun parse(json: String): Release {
            val o = JSONObject(json)
            val n = o.optJSONObject("notes")
            val file = o.getString("file")
            require(file.matches(Regex("[A-Za-z0-9._-]+\\.apk"))) { "bad file name" }
            return Release(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                file = file,
                bytes = o.getLong("bytes"),
                sha256 = o.getString("sha256").lowercase(),
                notes = n?.keys()?.asSequence()?.associateWith { n.getString(it) }.orEmpty(),
            )
        }
    }
}

object Updates {
    /** Tests point this at a local server. */
    internal var baseOverride: String? = null
    val enabled: Boolean get() = (baseOverride ?: BuildConfig.UPDATES_URL).isNotEmpty()
    private val base get() = (baseOverride ?: BuildConfig.UPDATES_URL).trimEnd('/')
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

    fun isNewer(r: Release, current: Int = BuildConfig.VERSION_CODE) = r.versionCode > current

    /** The newer release already found, if any. */
    fun available(ctx: Context): Release? =
        Prefs(ctx).availableUpdate?.let { runCatching { Release.parse(it) }.getOrNull() }?.takeIf { isNewer(it) }

    /** Network call; returns a newer release or null and remembers the result. Never throws. */
    fun check(ctx: Context): Release? {
        if (!enabled) return null
        val prefs = Prefs(ctx)
        return try {
            val conn = (URL("$base/latest.json?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "svitlograf-android/${BuildConfig.VERSION_NAME}")
            }
            prefs.updateCheckedAt = System.currentTimeMillis()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return available(ctx)
            val r = Release.parse(conn.inputStream.bufferedReader().use { it.readText() })
            prefs.availableUpdate = if (isNewer(r)) r.toJson() else null
            r.takeIf { isNewer(it) }
        } catch (e: Exception) {
            available(ctx)
        }
    }

    /** From the background worker: at most every 6 h, notify once per version. */
    fun checkInBackground(ctx: Context) {
        if (!enabled) return
        val prefs = Prefs(ctx)
        if (System.currentTimeMillis() - prefs.updateCheckedAt < CHECK_EVERY_MS) return
        val r = check(ctx) ?: return
        if (prefs.updateNotified != r.versionCode) {
            Notifier.updateAvailable(ctx, r.versionName)
            prefs.updateNotified = r.versionCode
        }
    }

    fun dueForCheck(ctx: Context) = enabled && System.currentTimeMillis() - Prefs(ctx).updateCheckedAt > 30 * 60_000L

    /** Downloads the APK into cache/updates, checking size and SHA-256. Blocking; call off the main thread. */
    fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != r.file }?.forEach { it.delete() }
        val target = File(dir, r.file)
        if (target.exists() && target.length() == r.bytes && sha256(target) == r.sha256) return target.also { onProgress(1f) }
        val part = File(dir, r.file + ".part")
        val conn = (URL("$base/${r.file}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "svitlograf-android/${BuildConfig.VERSION_NAME}")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress((done.toFloat() / r.bytes).coerceAtMost(1f))
                }
            }
        }
        verify(part, r)
        part.renameTo(target)
        return target
    }

    fun verify(f: File, r: Release) {
        if (f.length() != r.bytes) { f.delete(); throw IllegalStateException("size ${f.length()} != ${r.bytes}") }
        val actual = sha256(f)
        if (actual != r.sha256) { f.delete(); throw IllegalStateException("checksum mismatch") }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
