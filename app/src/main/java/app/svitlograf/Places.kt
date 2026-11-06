package app.svitlograf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One address the user follows: a region + outage group, shown by one or more widgets. */
data class Place(
    val id: Int,
    val name: String,
    val region: String?,
    val group: String?,
    val notifyChanges: Boolean = true,
    val notifyBefore: Boolean = true,
) {
    val configured: Boolean get() = region != null && group != null

    /** Places sharing region and group get one set of notifications and reminders. */
    val scheduleKey: String? get() = if (configured) "${region}_$group" else null

    fun displayName(ctx: Context): String = name.ifBlank { ctx.getString(R.string.place_default, id) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("region", region ?: JSONObject.NULL)
        .put("group", group ?: JSONObject.NULL)
        .put("notifyChanges", notifyChanges)
        .put("notifyBefore", notifyBefore)

    companion object {
        const val MAX = 6

        fun fromJson(o: JSONObject) = Place(
            id = o.getInt("id"),
            name = o.optString("name", ""),
            region = o.optString("region").takeIf { !o.isNull("region") && it.isNotEmpty() },
            group = o.optString("group").takeIf { !o.isNull("group") && it.isNotEmpty() },
            notifyChanges = o.optBoolean("notifyChanges", true),
            notifyBefore = o.optBoolean("notifyBefore", true),
        )

        fun listToJson(list: List<Place>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<Place> = runCatching {
            val a = JSONArray(raw ?: return emptyList())
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/** Groups configured places by schedule, keeping only those that want a given kind of alert. */
object Schedules {
    class Followed(val key: String, val region: String, val group: String, val places: List<Place>)

    fun followed(places: List<Place>, filter: (Place) -> Boolean = { true }): List<Followed> =
        places.filter { it.configured && filter(it) }
            .groupBy { it.scheduleKey!! }
            .map { (key, ps) -> Followed(key, ps.first().region!!, ps.first().group!!, ps) }
}
