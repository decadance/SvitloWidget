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
        ids.forEach { mgr.updateAppWidget(it, build(ctx, mgr.getAppWidgetOptions(it))) }
        Sync.schedulePeriodic(ctx)
        Alarms.reschedule(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        mgr.updateAppWidget(id, build(ctx, options))
    }

    override fun onEnabled(ctx: Context) {
        Sync.schedulePeriodic(ctx)
        Sync.now(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Sync.now(ctx)
            updateAll(ctx)
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_REFRESH = "app.svitlograf.REFRESH"
        private val hm = DateTimeFormatter.ofPattern("HH:mm")

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java)).forEach {
                mgr.updateAppWidget(it, build(ctx, mgr.getAppWidgetOptions(it)))
            }
        }

        fun build(
            ctx: Context,
            options: Bundle?,
            now: LocalDateTime = LocalDateTime.now(KYIV),
            region: RegionData? = Store.selected(ctx),
            elapsedNow: Long = SystemClock.elapsedRealtime(),
        ): RemoteViews {
            val prefs = Prefs(ctx)
            val group = prefs.group
            val configured = region != null && group != null
            val snap = Snapshot.build(region, group, now)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val density = ctx.resources.displayMetrics.density

            // Header
            val title = if (group != null) ctx.getString(R.string.group_fmt, group) else ctx.getString(R.string.app_name)
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

            // Size tiers: compact = today only; medium = + tomorrow; tall = + interval text.
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val widthDp = if (minW > 0) minW else 340
            val heightDp = if (maxH > 0) maxH else 260
            val compact = heightDp < 180
            val showTomorrow = heightDp >= 208
            val tall = heightDp >= 250

            if (configured) {
                val labels = Timeline.Labels(ctx.getString(R.string.today), ctx.getString(R.string.tomorrow), ctx.getString(R.string.no_schedule_yet))
                val l = Timeline.render(((widthDp - 32) * density).toInt(), density, snap.today, snap.tomorrow, now, showTomorrow, compact, labels)
                v.setImageViewBitmap(R.id.tl_track, l.track)
                v.setImageViewBitmap(R.id.tl_on, l.on)
                v.setImageViewBitmap(R.id.tl_off, l.off)
                v.setImageViewBitmap(R.id.tl_text, l.text)
                v.setImageViewBitmap(R.id.tl_marker, l.marker)
                v.setViewVisibility(R.id.timeline, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.timeline, View.GONE)
            }

            val lines = buildList {
                snap.today?.let { add(ctx.getString(R.string.intervals_today, Notifier.describe(ctx, it))) }
                snap.tomorrow?.let { add(ctx.getString(R.string.intervals_tomorrow, Notifier.describe(ctx, it))) }
            }
            v.setTextViewText(R.id.intervals, lines.joinToString("\n"))
            v.setViewVisibility(R.id.intervals, if (lines.isEmpty() || !tall || !configured) View.GONE else View.VISIBLE)

            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Widget::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            return v
        }
    }
}
