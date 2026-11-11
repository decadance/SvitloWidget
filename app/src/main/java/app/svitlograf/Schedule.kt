package app.svitlograf

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val KYIV: ZoneId = ZoneId.of("Europe/Kiev")
const val SLOTS = 48

/** One day of a group's schedule: off[i] == true means no power in half-hour slot i. */
class DaySlots(val date: LocalDate, val off: BooleanArray) {
    val signature: String get() = off.joinToString("") { if (it) "1" else "0" }
}

class RegionData(
    val id: String,
    val nameUk: String,
    val nameEn: String,
    val emergency: Boolean,
    val groups: Map<String, List<DaySlots>>,
) {
    fun days(group: String): List<DaySlots> = groups[group].orEmpty()
    fun name(lang: String) = if (lang == "uk") nameUk else nameEn
    val sortedGroups: List<String> get() = groups.keys.sortedWith(GroupOrder)
}

class Feed(val regions: List<RegionData>) {
    fun region(id: String?): RegionData? = regions.firstOrNull { it.id == id }
}

class Interval(val start: LocalDateTime, val end: LocalDateTime)

/** Real time between two Kyiv wall-clock moments, correct across the DST switch. */
fun kyivMillisBetween(from: LocalDateTime, to: LocalDateTime): Long =
    java.time.Duration.between(from.atZone(KYIV), to.atZone(KYIV)).toMillis()

/** Natural order: 1.1, 1.2, 2.1 … 10.1 */
object GroupOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return a.compareTo(b)
    }
}

object Parser {
    fun parse(json: String): Feed {
        val regions = JSONObject(json).getJSONArray("regions")
        return Feed((0 until regions.length()).map { parseRegion(regions.getJSONObject(it)) })
    }

    private fun parseRegion(r: JSONObject): RegionData {
        val groups = mutableMapOf<String, List<DaySlots>>()
        val sched = r.optJSONObject("schedule") ?: JSONObject()
        for (g in sched.keys()) {
            val byDate = sched.optJSONObject(g) ?: continue
            val days = mutableListOf<DaySlots>()
            for (d in byDate.keys()) {
                val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: continue
                val slots = byDate.optJSONObject(d) ?: continue
                val off = BooleanArray(SLOTS) { idx ->
                    val key = "%02d:%02d".format(idx / 2, (idx % 2) * 30)
                    slots.optInt(key, 1) != 1
                }
                days += DaySlots(date, off)
            }
            groups[g] = days.sortedBy { it.date }
        }
        val id = r.optString("cpu")
        return RegionData(
            id,
            r.optString("name_ua", id),
            r.optString("name_en", id),
            r.optBoolean("emergency", false),
            groups,
        )
    }
}

object Logic {
    fun slotIndex(t: LocalDateTime): Int = (t.hour * 60 + t.minute) / 30
    fun slotStart(date: LocalDate, idx: Int): LocalDateTime = date.atStartOfDay().plusMinutes(idx * 30L)

    /** Outage intervals, merged across midnight when consecutive days touch. */
    fun intervals(days: List<DaySlots>): List<Interval> {
        val out = mutableListOf<Interval>()
        var prevDate: LocalDate? = null
        for (day in days) {
            val contiguous = prevDate != null && day.date == prevDate.plusDays(1)
            var i = 0
            while (i < SLOTS) {
                if (!day.off[i]) { i++; continue }
                var j = i
                while (j < SLOTS && day.off[j]) j++
                val start = slotStart(day.date, i)
                val end = slotStart(day.date, j)
                val last = out.lastOrNull()
                if (i == 0 && contiguous && last != null && last.end == start) {
                    out[out.size - 1] = Interval(last.start, end)
                } else {
                    out += Interval(start, end)
                }
                i = j
            }
            prevDate = day.date
        }
        return out
    }

    fun hm(t: LocalDateTime, day: LocalDate): String =
        if (t.toLocalDate() != day && t.hour == 0 && t.minute == 0) "24:00" else "%02d:%02d".format(t.hour, t.minute)

    /** "03:30–10:30, 14:00–18:00"; empty list → null so callers can pick the "no outages" wording. */
    fun formatDay(day: DaySlots): String? {
        val list = intervals(listOf(day))
        if (list.isEmpty()) return null
        return list.joinToString(", ") { "${hm(it.start, day.date)}–${hm(it.end, day.date)}" }
    }
}

enum class Power { ON, OFF, UNKNOWN }

class Snapshot(
    val power: Power,
    /** When the current state ends, if that happens within known data. */
    val nextChange: LocalDateTime?,
    val today: DaySlots?,
    val tomorrow: DaySlots?,
    val emergency: Boolean,
) {
    companion object {
        fun build(region: RegionData?, group: String?, now: LocalDateTime): Snapshot {
            val days = if (region != null && group != null) region.days(group) else emptyList()
            val today = days.firstOrNull { it.date == now.toLocalDate() }
            val tomorrow = days.firstOrNull { it.date == now.toLocalDate().plusDays(1) }
            if (today == null) return Snapshot(Power.UNKNOWN, null, null, tomorrow, region?.emergency ?: false)
            val idx = Logic.slotIndex(now)
            val cur = today.off[idx]
            var next: LocalDateTime? = null
            outer@ for ((n, d) in listOfNotNull(today, tomorrow).withIndex()) {
                for (i in (if (n == 0) idx + 1 else 0) until SLOTS) {
                    if (d.off[i] != cur) { next = Logic.slotStart(d.date, i); break@outer }
                }
            }
            return Snapshot(if (cur) Power.OFF else Power.ON, next, today, tomorrow, region?.emergency ?: false)
        }
    }
}

sealed class Change {
    data class NewDay(val date: LocalDate) : Change()
    data class DayChanged(val date: LocalDate) : Change()
}

object Diff {
    /**
     * Compares against signatures already announced (not the previous fetch),
     * so a day that briefly vanishes from the feed and returns unchanged doesn't notify twice.
     */
    fun changes(seen: Map<LocalDate, String>, region: RegionData, group: String, today: LocalDate): List<Change> =
        region.days(group).filter { it.date >= today }.mapNotNull { d ->
            when (seen[d.date]) {
                null -> Change.NewDay(d.date)
                d.signature -> null
                else -> Change.DayChanged(d.date)
            }
        }
}
