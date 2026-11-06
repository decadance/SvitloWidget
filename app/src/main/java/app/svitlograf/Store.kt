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

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init { migrate() }

    // --- places ---

    val places: List<Place> get() = Place.listFromJson(sp.getString("places", null))

    fun place(id: Int): Place? = places.firstOrNull { it.id == id }

    private fun savePlaces(list: List<Place>) = sp.edit().putString("places", Place.listToJson(list)).apply()

    fun addPlace(name: String = ""): Place {
        val next = sp.getInt("next_place_id", 1)
        val p = Place(next, name, null, null)
        sp.edit().putInt("next_place_id", next + 1).apply()
        savePlaces(places + p)
        return p
    }

    fun updatePlace(p: Place) = savePlaces(places.map { if (it.id == p.id) p else it })

    /** Removes a place; widgets showing it fall back to the first remaining one. Keeps at least one place. */
    fun deletePlace(id: Int) {
        val rest = places.filter { it.id != id }
        if (rest.isEmpty()) return
        savePlaces(rest)
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("widget_") && sp.getInt(it, -1) == id }.forEach { e.remove(it) }
        e.apply()
        if (selectedPlace == id) selectedPlace = rest.first().id
    }

    var selectedPlace: Int
        get() = sp.getInt("selected_place", places.firstOrNull()?.id ?: 1)
        set(v) = sp.edit().putInt("selected_place", v).apply()

    fun bindWidget(widgetId: Int, placeId: Int) = sp.edit().putInt("widget_$widgetId", placeId).apply()
    fun unbindWidget(widgetId: Int) = sp.edit().remove("widget_$widgetId").apply()
    fun isBound(widgetId: Int) = sp.contains("widget_$widgetId")

    /** The place a widget shows; unbound widgets (e.g. placed before 1.1) show the first place. */
    fun placeForWidget(widgetId: Int): Place? {
        val all = places
        val id = sp.getInt("widget_$widgetId", -1)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun widgetsFor(placeId: Int): List<Int> =
        sp.all.filter { (k, v) -> k.startsWith("widget_") && v == placeId }.keys.mapNotNull { it.removePrefix("widget_").toIntOrNull() }

    // --- global settings and state ---

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

    fun lastReminded(key: String): Long = sp.getLong("reminded_$key", 0)
    fun setLastReminded(key: String, startMs: Long) = sp.edit().putLong("reminded_$key", startMs).apply()

    fun emergencySeen(region: String): Boolean = sp.getBoolean("emergency_$region", false)
    fun setEmergencySeen(region: String, v: Boolean) = sp.edit().putBoolean("emergency_$region", v).apply()

    /** Request codes of reminder alarms that may be pending, so removed places' alarms get cancelled. */
    var reminderCodes: Set<Int>
        get() = sp.getStringSet("reminder_codes", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("reminder_codes", v.map { it.toString() }.toSet()).apply()

    private fun seenKey(region: String, group: String) = "seen_${region}_$group"

    fun seen(region: String, group: String): Map<LocalDate, String>? {
        val raw = sp.getString(seenKey(region, group), null) ?: return null
        val o = JSONObject(raw)
        return o.keys().asSequence().associate { LocalDate.parse(it) to o.getString(it) }
    }

    fun setSeen(region: String, group: String, map: Map<LocalDate, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k.toString(), v) }
        sp.edit().putString(seenKey(region, group), o.toString()).apply()
    }

    // --- app updates (GitHub build only) ---

    var updateCheckedAt: Long
        get() = sp.getLong("update_checked_at", 0)
        set(v) = sp.edit().putLong("update_checked_at", v).apply()
    var availableUpdate: String?
        get() = sp.getString("update_available", null)
        set(v) = sp.edit().putString("update_available", v).apply()
    var updateNotified: Int
        get() = sp.getInt("update_notified", 0)
        set(v) = sp.edit().putInt("update_notified", v).apply()

    /** 1.0 kept a single region/group; turn it into the first place. Runs once. */
    private fun migrate() {
        if (sp.contains("places")) return
        val first = Place(
            id = 1,
            name = "",
            region = sp.getString("region", null),
            group = sp.getString("group", null),
            notifyChanges = sp.getBoolean("notify_changes", true),
            notifyBefore = sp.getBoolean("notify_before", true),
        )
        val e = sp.edit()
            .putString("places", Place.listToJson(listOf(first)))
            .putInt("next_place_id", 2)
            .putInt("selected_place", 1)
        if (first.region != null) e.putBoolean("emergency_${first.region}", sp.getBoolean("emergency_seen", false))
        if (first.scheduleKey != null) e.putLong("reminded_${first.scheduleKey}", sp.getLong("last_reminded", 0))
        listOf("region", "group", "notify_changes", "notify_before", "emergency_seen", "last_reminded").forEach { e.remove(it) }
        e.apply()
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
        val places = prefs.places
        val today = LocalDateTime.now(KYIV).toLocalDate()
        val multi = places.size > 1
        for (s in Schedules.followed(places)) {
            val region = feed.region(s.region) ?: continue
            val seen = prefs.seen(s.region, s.group)
            val wantChanges = s.places.any { it.notifyChanges }
            if (seen != null && wantChanges) {
                val changes = Diff.changes(seen, region, s.group, today)
                if (changes.isNotEmpty()) Notifier.scheduleChanged(ctx, s, region, changes, multi)
            }
            val current = region.days(s.group).filter { it.date >= today }.associate { it.date to it.signature }
            prefs.setSeen(s.region, s.group, seen.orEmpty().filterKeys { it >= today } + current)
        }
        for (regionId in places.filter { it.configured && it.notifyChanges }.mapNotNull { it.region }.distinct()) {
            val region = feed.region(regionId) ?: continue
            if (region.emergency != prefs.emergencySeen(regionId)) Notifier.emergency(ctx, region, region.emergency)
            prefs.setEmergencySeen(regionId, region.emergency)
        }
    }

    /** After a place changes region/group, treat what is already published for it as seen. */
    fun reseed(ctx: Context, place: Place) {
        val prefs = Prefs(ctx)
        val region = load(ctx)?.region(place.region) ?: return
        val group = place.group ?: return
        val today = LocalDateTime.now(KYIV).toLocalDate()
        prefs.setEmergencySeen(region.id, region.emergency)
        if (prefs.seen(region.id, group) == null) {
            prefs.setSeen(region.id, group, region.days(group).filter { it.date >= today }.associate { it.date to it.signature })
        }
    }
}
