package app.svitlograf

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
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
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && placeId > 0) {
                    Prefs(ctx).bindWidget(widgetId, placeId)
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
            ctx: Context,
            place: Place?,
            options: Bundle?,
            now: LocalDateTime = LocalDateTime.now(KYIV),
            region: RegionData? = Store.load(ctx)?.region(place?.region),
            elapsedNow: Long = SystemClock.elapsedRealtime(),
            clickCode: Int = 0,
        ): RemoteViews {
            val prefs = Prefs(ctx)
            val group = place?.group
            val configured = region != null && group != null
            val snap = Snapshot.build(region, group, now)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val density = ctx.resources.displayMetrics.density

            // Header: with several addresses the widget says which one it shows.
            val multi = prefs.places.size > 1
            val title = when {
                place != null && multi && group != null -> ctx.getString(R.string.place_group_fmt, place.displayName(ctx), group)
                place != null && multi -> place.displayName(ctx)
                group != null -> ctx.getString(R.string.group_fmt, group)
                else -> ctx.getString(R.string.app_name)
            }
            val fetched = prefs.fetchedAt
            val subtitle = if (fetched == 0L) title else {
                val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(fetched), KYIV).format(hm)
                val stale = System.currentTimeMillis() - fetched > 60 * 60_000
                ctx.getString(if (stale) R.string.updated_stale_fmt else R.string.updated_fmt, title, t)
            }
            v.setTextViewText(R.id.subtitle, subtitle)

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

            // Next change pill
            val next = snap.nextChange
            if (configured && next != null && snap.power != Power.UNKNOWN) {
                val tomorrow = next.toLocalDate() != now.toLocalDate() && !(next.hour == 0 && next.minute == 0)
                val time = if (next.toLocalDate() != now.toLocalDate() && next.hour == 0 && next.minute == 0) "24:00" else next.format(hm)
                val res = when {
                    snap.power == Power.ON && !tomorrow -> R.string.next_off
                    snap.power == Power.ON -> R.string.next_off_tomorrow
                    !tomorrow -> R.string.next_on
                    else -> R.string.next_on_tomorrow
                }
                v.setTextViewText(R.id.next_label, ctx.getString(res, time))
                v.setChronometer(R.id.countdown, elapsedNow + Duration.between(now, next).toMillis(), null, true)
                v.setChronometerCountDown(R.id.countdown, true)
                v.setViewVisibility(R.id.countdown, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.countdown, View.GONE)
                v.setTextViewText(
                    R.id.next_label,
                    ctx.getString(
                        when {
                            !configured -> R.string.setup_hint
                            snap.power == Power.UNKNOWN -> R.string.tap_refresh
                            snap.tomorrow == null && snap.power == Power.ON -> R.string.no_more_today
                            snap.tomorrow == null -> R.string.until_eod
                            else -> R.string.no_change
                        },
                    ),
                )
            }
            v.setViewVisibility(R.id.alert, if (snap.emergency && configured) View.VISIBLE else View.GONE)

            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val widthDp = if (minW > 0) minW else 340
            val heightDp = if (maxH > 0) maxH else 260

            val lines = buildList {
                snap.today?.let { add(ctx.getString(R.string.intervals_today, Notifier.describe(ctx, it))) }
                snap.tomorrow?.let { add(ctx.getString(R.string.intervals_tomorrow, Notifier.describe(ctx, it))) }
            }
            val fit = WidgetFit.choose(heightDp, ctx.resources.configuration.fontScale, lines.size, snap.emergency && configured)

            if (configured) {
                val labels = Timeline.Labels(ctx.getString(R.string.today), ctx.getString(R.string.tomorrow), ctx.getString(R.string.no_schedule_yet))
                val l = Timeline.render(((widthDp - 32) * density).toInt(), density, snap.today, snap.tomorrow, now, fit.rows == 2, fit.compact, labels)
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
    }
}

/**
 * Picks what fits into the widget height. Launchers only offer whole grid cells, so instead of fixed
 * tiers the parts are added by priority: the "Today: 09:30–13:30" line beats the tomorrow bar.
 */
object WidgetFit {
    data class Fit(val rows: Int, val compact: Boolean, val lines: Int)

    // Heights in dp, measured from layout/widget.xml with real fonts (see WidgetFitTest); text parts grow with font size.
    private const val PADDING = 32f
    private const val TIMELINE_MARGIN = 14f
    private const val ONE_ROW_COMPACT = 26f
    private const val ONE_ROW = 42f
    private const val TWO_ROWS = 68f

    fun choose(heightDp: Int, fontScale: Float, lineCount: Int, alert: Boolean): Fit {
        val fs = fontScale.coerceAtLeast(0.85f)
        val header = 14f + 33f * fs
        val pill = 12f + 12f + 21f * fs
        val line = 15.7f * fs + 2f
        val alertH = if (alert) 8f + 2 * line else 0f
        val room = heightDp - PADDING - header - pill - alertH - TIMELINE_MARGIN
        fun linesH(n: Int) = if (n == 0) 0f else 8f + n * line

        val today = minOf(lineCount, 1)
        val candidates = listOf(
            Fit(2, false, lineCount),
            Fit(2, false, today),
            Fit(1, false, today),
            Fit(1, true, today),
            Fit(1, true, 0),
        )
        return candidates.firstOrNull { c ->
            val tl = when { c.rows == 2 -> TWO_ROWS; c.compact -> ONE_ROW_COMPACT; else -> ONE_ROW }
            tl + linesH(c.lines) <= room
        } ?: candidates.last()
    }
}
