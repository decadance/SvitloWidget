package app.svitlograf

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.StaticLayout
import android.util.TypedValue
import android.text.TextPaint
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
        Sync.schedulePeriodic(ctx)
        Alarms.reschedule(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        update(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val prefs = Prefs(ctx)
        ids.forEach { prefs.unbindWidget(it) }
    }

    override fun onEnabled(ctx: Context) {
        Sync.schedulePeriodic(ctx)
        Sync.now(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                Sync.now(ctx)
                updateAll(ctx)
            }
            // Result of requestPinAppWidget from the app: bind the new widget to the chosen address.
            ACTION_PINNED -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val placeId = intent.getIntExtra(EXTRA_PLACE, -1)
                val prefs = Prefs(ctx)
                // The launcher may also show the config screen; whatever was picked there wins.
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && placeId > 0 && !prefs.isBound(widgetId)) {
                    prefs.bindWidget(widgetId, placeId)
                    update(ctx, AppWidgetManager.getInstance(ctx), widgetId)
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.svitlograf.REFRESH"
        const val ACTION_PINNED = "app.svitlograf.PINNED"
        const val EXTRA_PLACE = "place"
        private val hm = DateTimeFormatter.ofPattern("HH:mm")

        fun update(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val place = Prefs(ctx).placeForWidget(widgetId)
            mgr.updateAppWidget(widgetId, build(ctx, place, mgr.getAppWidgetOptions(widgetId), clickCode = widgetId))
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java)).forEach { update(ctx, mgr, it) }
        }

        fun build(
            context: Context,
            place: Place?,
            options: Bundle?,
            now: LocalDateTime = LocalDateTime.now(KYIV),
            region: RegionData? = Store.load(context)?.region(place?.region),
            elapsedNow: Long = SystemClock.elapsedRealtime(),
            clickCode: Int = 0,
        ): RemoteViews {
            val ctx = context.localized()
            val prefs = Prefs(ctx)
            val group = place?.group
            val configured = region != null && group != null
            val snap = Snapshot.build(region, group, now)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val density = ctx.resources.displayMetrics.density

            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val widthDp = if (minW > 0) minW else 340
            val heightDp = if (maxH > 0) maxH else 260

            val lines = buildList {
                snap.today?.let { add(ctx.getString(R.string.intervals_today, Notifier.describe(ctx, it))) }
                snap.tomorrow?.let { add(ctx.getString(R.string.intervals_tomorrow, Notifier.describe(ctx, it))) }
            }
            val rows = if (configured) upcomingRows(ctx, snap, now) else emptyList()
            // Narrow widgets wrap the interval text and the emergency banner; count the lines they really take.
            val fontScale = ctx.resources.configuration.fontScale
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * density * fontScale }
            val textWidth = ((widthDp - 32) * density).toInt().coerceAtLeast(1)
            fun screenLines(text: String) =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth).build().lineCount
            val alertLines = if (snap.emergency && configured) screenLines(ctx.getString(R.string.emergency_banner)) else 0
            val fit = WidgetFit.choose(
                heightDp, widthDp, fontScale, lines.map(::screenLines), alertLines, rows.size,
            )

            // Header: with several addresses the widget says which one it shows.
            val multi = prefs.places.size > 1
            val title = when {
                place != null && multi && group != null -> ctx.getString(R.string.place_group_fmt, place.displayName(ctx), group)
                place != null && multi -> place.displayName(ctx)
                group != null -> ctx.getString(R.string.group_fmt, group)
                else -> ctx.getString(R.string.app_name)
            }
            val fetched = prefs.fetchedAt
            val stale = System.currentTimeMillis() - fetched > 60 * 60_000
            val fresh = region != null && !stale
            val subtitle = if (fetched == 0L) title else {
                val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(fetched), KYIV).format(hm)
                ctx.getString(if (stale) R.string.updated_stale_fmt else R.string.updated_fmt, title, t)
            }

            when {
                !configured -> {
                    v.setTextViewText(R.id.status, ctx.getString(R.string.power_setup))
                    v.setImageViewResource(R.id.badge, R.drawable.badge_unknown)
                }
                snap.power == Power.ON -> {
                    v.setTextViewText(R.id.status, ctx.getString(R.string.power_on))
                    v.setImageViewResource(R.id.badge, R.drawable.badge_on)
                }
                snap.power == Power.OFF -> {
                    v.setTextViewText(R.id.status, ctx.getString(R.string.power_off))
                    v.setImageViewResource(R.id.badge, R.drawable.badge_off)
                }
                else -> {
                    v.setTextViewText(R.id.status, ctx.getString(R.string.power_unknown))
                    v.setImageViewResource(R.id.badge, R.drawable.badge_unknown)
                }
            }

            // Next change: a pill with a live countdown, or a plain line under the status when there's no room.
            val next = snap.nextChange.takeIf { configured && snap.power != Power.UNKNOWN }
            val idle = when {
                !configured -> R.string.setup_hint
                snap.power == Power.UNKNOWN && fresh -> R.string.not_published
                snap.power == Power.UNKNOWN -> R.string.tap_refresh
                snap.tomorrow == null && snap.power == Power.ON -> R.string.no_more_today
                snap.tomorrow == null -> R.string.until_eod
                else -> R.string.no_change
            }
            if (next != null) {
                val midnight = next.toLocalDate() != now.toLocalDate() && next.hour == 0 && next.minute == 0
                val tomorrow = next.toLocalDate() != now.toLocalDate() && !midnight
                val time = if (midnight) "24:00" else next.format(hm)
                val on = snap.power == Power.ON
                val label = when {
                    fit.short && tomorrow -> R.string.next_until_tomorrow
                    fit.short -> R.string.next_until
                    on && !tomorrow -> R.string.next_off
                    on -> R.string.next_off_tomorrow
                    !tomorrow -> R.string.next_on
                    else -> R.string.next_on_tomorrow
                }
                v.setTextViewText(R.id.next_label, ctx.getString(label, time))
                v.setChronometer(R.id.countdown, elapsedNow + kyivMillisBetween(now, next), null, true)
                v.setChronometerCountDown(R.id.countdown, true)
                v.setViewVisibility(R.id.countdown, View.VISIBLE)
                val plain = when {
                    on && !tomorrow -> R.string.next_off_at
                    on -> R.string.next_off_at_tomorrow
                    !tomorrow -> R.string.next_on_at
                    else -> R.string.next_on_at_tomorrow
                }
                v.setTextViewText(R.id.subtitle, if (fit.pill) subtitle else ctx.getString(plain, time))
            } else {
                v.setViewVisibility(R.id.countdown, View.GONE)
                v.setTextViewText(R.id.next_label, ctx.getString(idle))
                v.setTextViewText(R.id.subtitle, if (fit.pill || !configured) subtitle else ctx.getString(idle))
            }
            v.setViewVisibility(R.id.pill, if (fit.pill) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.refresh, if (fit.refresh) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.badge, if (fit.badge) View.VISIBLE else View.GONE)
            if (fit.hero) {
                v.setTextViewTextSize(R.id.status, TypedValue.COMPLEX_UNIT_SP, 30f)
                v.setTextViewTextSize(R.id.next_label, TypedValue.COMPLEX_UNIT_SP, 16f)
                v.setTextViewTextSize(R.id.countdown, TypedValue.COMPLEX_UNIT_SP, 16f)
            }
            v.setViewVisibility(R.id.alert, if (snap.emergency && configured && fit.alert) View.VISIBLE else View.GONE)
            if (snap.emergency && configured && !fit.alert) v.setTextViewText(R.id.subtitle, ctx.getString(R.string.notif_emergency_on))

            if (configured && fit.timeline) {
                val labels = Timeline.Labels(ctx.getString(R.string.today), ctx.getString(R.string.tomorrow), ctx.getString(R.string.no_schedule_yet))
                val l = Timeline.render(
                    ((widthDp - 32) * density).toInt(), density, snap.today, snap.tomorrow, now,
                    fit.rows == 2, fit.labels, fit.scale, labels, fit.barDp,
                )
                v.setImageViewBitmap(R.id.tl_track, l.track)
                v.setImageViewBitmap(R.id.tl_on, l.on)
                v.setImageViewBitmap(R.id.tl_off, l.off)
                v.setImageViewBitmap(R.id.tl_text, l.text)
                v.setImageViewBitmap(R.id.tl_marker, l.marker)
                v.setViewVisibility(R.id.timeline, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.timeline, View.GONE)
            }

            v.setTextViewText(R.id.intervals, lines.take(fit.lines).joinToString("\n"))
            v.setViewVisibility(R.id.intervals, if (fit.lines == 0 || !configured) View.GONE else View.VISIBLE)

            // Tall widgets: the upcoming outages as a list instead of empty space.
            v.removeAllViews(R.id.upcoming)
            rows.take(fit.list).forEach { r ->
                val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                if (fit.rowExtra > 0) {
                    val pad = ((6 + fit.rowExtra / 2) * density).toInt()
                    val side = (12 * density).toInt()
                    row.setViewPadding(R.id.row_root, side, pad, side, pad)
                }
                row.setTextViewText(R.id.row_day, r.day)
                row.setTextViewText(R.id.row_time, r.time)
                row.setTextViewText(R.id.row_duration, r.duration)
                row.setViewVisibility(R.id.row_dot, if (r.outage) View.VISIBLE else View.INVISIBLE)
                if (!r.outage) row.setTextColor(R.id.row_time, ctx.getColor(R.color.w_on_surface_variant))
                v.addView(R.id.upcoming, row)
            }
            val showList = fit.list > 0 && configured
            v.setViewVisibility(R.id.upcoming_title, if (showList) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.upcoming, if (showList) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.spacer, if (showList) View.VISIBLE else View.GONE)

            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { place?.let { putExtra(MainActivity.EXTRA_PLACE, it.id) } }
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(ctx, clickCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Widget::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            return v
        }

        class Row(val day: String, val time: String, val duration: String, val outage: Boolean)

        /** Outages still ahead (or going on), then a line about tomorrow if its schedule isn't known. */
        fun upcomingRows(ctx: Context, snap: Snapshot, now: LocalDateTime): List<Row> {
            val today = now.toLocalDate()
            val ahead = Logic.intervals(listOfNotNull(snap.today, snap.tomorrow)).filter { it.end.isAfter(now) }
            val rows = mutableListOf<Row>()
            if (snap.today != null && ahead.none { it.start.toLocalDate() == today }) {
                rows += Row(ctx.getString(R.string.today), ctx.getString(R.string.row_no_more), "", false)
            }
            ahead.forEach { iv ->
                val day = iv.start.toLocalDate()
                val label = when {
                    !iv.start.isAfter(now) -> ctx.getString(R.string.now_label)
                    day == today -> ctx.getString(R.string.today)
                    else -> ctx.getString(R.string.tomorrow)
                }
                rows += Row(label, "${Logic.hm(iv.start, day)}–${Logic.hm(iv.end, day)}", duration(ctx, kyivMillisBetween(iv.start, iv.end)), true)
            }
            when {
                snap.tomorrow == null && snap.today != null -> rows += Row(ctx.getString(R.string.tomorrow), ctx.getString(R.string.no_schedule_yet), "", false)
                snap.tomorrow != null && Logic.intervals(listOf(snap.tomorrow)).isEmpty() ->
                    rows += Row(ctx.getString(R.string.tomorrow), ctx.getString(R.string.no_outages), "", false)
            }
            return rows
        }

        private fun duration(ctx: Context, ms: Long): String {
            val m = (ms / 60_000).toInt()
            return when {
                m % 60 == 0 -> ctx.getString(R.string.dur_h, m / 60)
                m < 60 -> ctx.getString(R.string.dur_m, m)
                else -> ctx.getString(R.string.dur_hm, m / 60, m % 60)
            }
        }
    }
}

/**
 * Picks what fits into the widget. Launchers only offer whole grid cells, so instead of fixed tiers:
 * - tall: the upcoming outages as a list, and thicker bars with whatever is left;
 * - medium: parts are dropped one by one — tomorrow's interval text, the hour scale, the tomorrow
 *   bar, the bar labels and finally today's interval text;
 * - short: the timeline goes, then the countdown pill (the next switch moves under the status);
 * - narrow: a shorter countdown label ("until 18:00 · 2:01:15").
 */
object WidgetFit {
    data class Fit(
        val rows: Int,
        val labels: Boolean,
        val scale: Boolean,
        val lines: Int,
        val list: Int = 0,
        val barDp: Float = Timeline.BAR_DP,
        val timeline: Boolean = true,
        val pill: Boolean = true,
        val short: Boolean = false,
        val alert: Boolean = true,
        /** Extra vertical padding per list row, dp. */
        val rowExtra: Float = 0f,
        /** Bigger status and countdown for tall widgets. */
        val hero: Boolean = false,
        val refresh: Boolean = true,
        val badge: Boolean = true,
    )

    // Heights in dp, measured from the layouts with real fonts (see WidgetFitTest); text parts grow with font size.
    private const val PADDING = 32f
    private const val PILL_MARGIN = 10f
    private const val TIMELINE_MARGIN = 10f
    private const val LINES_MARGIN = 6f
    private const val LIST_MARGINS = 12f + 6f
    private const val MAX_BAR = 26f
    private const val MAX_ROW_EXTRA = 10f
    private const val NO_REFRESH = 300
    private const val NO_BADGE = 230
    private const val NARROW_TEXT = 260f
    /** Hero status (30sp instead of 22sp) and countdown (16sp instead of 14sp) cost this much more height. */
    const val HERO_EXTRA = 13f

    /** [lineCounts]: screen lines each interval text ("Today: …", "Tomorrow: …") takes; [alertLines]: the emergency banner's. */
    fun choose(heightDp: Int, widthDp: Int, fontScale: Float, lineCounts: List<Int>, alertLines: Int, listCount: Int = 0): Fit {
        val fs = fontScale.coerceAtLeast(0.85f)
        // "Back at 14:30 · in 1:48:55" needs ~260dp at normal font size.
        val short = widthDp - PADDING < NARROW_TEXT * fs
        val header = 14f + 33f * fs
        val pill = PILL_MARGIN + 12f + 21f * fs
        val line = 15.7f * fs + 2f
        val alertH = if (alertLines > 0) 8f + alertLines * line else 0f
        val listTitle = 16.4f * fs
        val row = 16f + 19.4f * fs
        val top = heightDp - PADDING - header
        val refresh = widthDp >= NO_REFRESH
        val badge = widthDp >= NO_BADGE
        fun Fit.header() = copy(short = short, refresh = refresh, badge = badge)

        if (top < pill) return Fit(1, false, false, 0, timeline = false, pill = false, alert = false).header()
        // A tiny widget swaps the emergency banner for a short note under the status.
        if (top < pill + alertH) return Fit(1, false, false, 0, timeline = false, alert = false).header()
        val room = top - pill - alertH - TIMELINE_MARGIN
        if (room < Timeline.heightDp(1, false)) return Fit(1, false, false, 0, timeline = false).header()

        val full = Timeline.heightDp(2, true)
        val listSpace = room - full - LIST_MARGINS - listTitle
        val listRows = minOf(listCount, (listSpace / row).toInt().coerceAtLeast(0))
        if (listCount > 0 && listRows >= minOf(listCount, 2)) {
            // Space left after the list goes, in turn, to thicker bars, roomier rows and a bigger status.
            var left = listSpace - listRows * row
            val bar = (Timeline.BAR_DP + left / 2).coerceAtMost(MAX_BAR)
            left -= (bar - Timeline.BAR_DP) * 2
            val extra = (left / listRows).coerceIn(0f, MAX_ROW_EXTRA)
            left -= extra * listRows
            val hero = left >= HERO_EXTRA * fs
            return Fit(2, true, true, 0, list = listRows, barDp = bar, rowExtra = extra, hero = hero).header()
        }

        fun linesH(n: Int) = if (n == 0) 0f else LINES_MARGIN + lineCounts.take(n).sum() * line
        val all = lineCounts.size
        val today = minOf(all, 1)
        val candidates = listOf(
            Fit(2, true, true, all),
            Fit(2, true, false, all),
            Fit(2, true, true, today),
            Fit(2, true, false, today),
            Fit(1, true, true, today),
            Fit(1, false, false, today),
            Fit(1, false, false, 0),
        )
        return (candidates.firstOrNull { Timeline.heightDp(it.rows, it.scale) + linesH(it.lines) <= room } ?: candidates.last())
            .header()
    }
}
