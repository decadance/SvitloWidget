package app.svitlograf

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

const val SOURCE_URL =
    "https://raw.githubusercontent.com/chaichuk/UA-power-outages-monitor/main/artifacts/last-message.json"
const val SOURCE_PAGE = "https://github.com/chaichuk/UA-power-outages-monitor"

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var region: String?
        get() = sp.getString("region", null)
        set(v) = sp.edit().putString("region", v).apply()
    var group: String?
        get() = sp.getString("group", null)
        set(v) = sp.edit().putString("group", v).apply()
    var notifyChanges: Boolean
        get() = sp.getBoolean("notify_changes", true)
        set(v) = sp.edit().putBoolean("notify_changes", v).apply()
    var notifyBefore: Boolean
        get() = sp.getBoolean("notify_before", true)
        set(v) = sp.edit().putBoolean("notify_before", v).apply()
    var minutesBefore: Int
        get() = sp.getInt("minutes_before", 15)
        set(v) = sp.edit().putInt("minutes_before", v).apply()
    var fetchedAt: Long
        get() = sp.getLong("fetched_at", 0)
        set(v) = sp.edit().putLong("fetched_at", v).apply()
    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()
    var etag: String?
        get() = sp.getString("etag", null)
        set(v) = sp.edit().putString("etag", v).apply()
    var lastRemindedStart: Long
        get() = sp.getLong("last_reminded", 0)
        set(v) = sp.edit().putLong("last_reminded", v).apply()
    var emergencySeen: Boolean
        get() = sp.getBoolean("emergency_seen", false)
        set(v) = sp.edit().putBoolean("emergency_seen", v).apply()

    private fun key(region: String, group: String) = "seen_${region}_$group"

    fun seen(region: String, group: String): Map<LocalDate, String>? {
        val raw = sp.getString(key(region, group), null) ?: return null
        val o = JSONObject(raw)
        return o.keys().asSequence().associate { LocalDate.parse(it) to o.getString(it) }
    }

    fun setSeen(region: String, group: String, map: Map<LocalDate, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k.toString(), v) }
        sp.edit().putString(key(region, group), o.toString()).apply()
    }

    companion object { const val NAME = "svitlograf" }
}

object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "feed.json")

    @Volatile private var cache: Pair<Long, Feed>? = null

    fun load(ctx: Context): Feed? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        val stamp = f.lastModified() xor f.length()
        cache?.takeIf { it.first == stamp }?.second ?: Parser.parse(f.readText()).also { cache = stamp to it }
    }.getOrNull()

    fun selected(ctx: Context): RegionData? = load(ctx)?.region(Prefs(ctx).region)

    sealed class Result {
        object Unchanged : Result()
        class Updated(val feed: Feed) : Result()
        class Failed(val error: String) : Result()
    }

    /** ~7 KB gzipped; the whole feed is kept so switching region needs no refetch. */
    fun fetch(ctx: Context): Result {
        val prefs = Prefs(ctx)
        return try {
            val conn = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "svitlograf-android")
                prefs.etag?.let { if (file(ctx).exists()) setRequestProperty("If-None-Match", it) }
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    prefs.fetchedAt = System.currentTimeMillis(); prefs.lastError = null
                    Result.Unchanged
                }
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val feed = Parser.parse(body)
                    val tmp = File(ctx.filesDir, "feed.json.tmp").apply { writeText(body) }
                    tmp.renameTo(file(ctx))
                    cache = null
                    prefs.etag = conn.getHeaderField("ETag")
                    prefs.fetchedAt = System.currentTimeMillis(); prefs.lastError = null
                    Result.Updated(feed)
                }
                else -> Result.Failed("HTTP $code").also { prefs.lastError = it.error }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            prefs.lastError = msg
            Result.Failed(msg)
        }
    }

    fun sync(ctx: Context): Boolean {
        val res = fetch(ctx)
        if (res is Result.Updated) announce(ctx, res.feed)
        Widget.updateAll(ctx)
        Alarms.reschedule(ctx)
        return res !is Result.Failed
    }

    private fun announce(ctx: Context, feed: Feed) {
        val prefs = Prefs(ctx)
        val region = feed.region(prefs.region) ?: return
        val group = prefs.group ?: return
        val today = LocalDateTime.now(KYIV).toLocalDate()
        val seen = prefs.seen(region.id, group)
        if (seen != null && prefs.notifyChanges) {
            val changes = Diff.changes(seen, region, group, today)
            if (changes.isNotEmpty()) Notifier.scheduleChanged(ctx, region, group, changes)
            if (region.emergency != prefs.emergencySeen) Notifier.emergency(ctx, region, region.emergency)
        }
        prefs.emergencySeen = region.emergency
        val current = region.days(group).filter { it.date >= today }.associate { it.date to it.signature }
        prefs.setSeen(region.id, group, seen.orEmpty().filterKeys { it >= today } + current)
    }

    /** After the user picks a region/group, treat what is already published as seen. */
    fun reseed(ctx: Context) {
        val prefs = Prefs(ctx)
        val region = selected(ctx) ?: return
        val group = prefs.group ?: return
        val today = LocalDateTime.now(KYIV).toLocalDate()
        prefs.emergencySeen = region.emergency
        prefs.setSeen(region.id, group, region.days(group).filter { it.date >= today }.associate { it.date to it.signature })
    }
}
