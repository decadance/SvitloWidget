package app.svitlograf

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Context.locale(): Locale = resources.configuration.locales[0]
fun Context.lang(): String = if (locale().language == "uk") "uk" else "en"

object Notifier {
    private const val CH_CHANGES = "changes"
    private const val CH_REMIND = "reminders"
    private const val ID_CHANGES = 1
    private const val ID_REMIND = 2
    private const val ID_EMERGENCY = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CHANGES, ctx.getString(R.string.ch_changes), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.ch_changes_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, ctx.getString(R.string.ch_remind), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.ch_remind_desc) }
        )
    }

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String) {
        if (!canPost(ctx)) return
        createChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ctx.getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun dayLabel(ctx: Context, date: LocalDate, today: LocalDate = LocalDateTime.now(KYIV).toLocalDate()): String {
        val fmt = DateTimeFormatter.ofPattern("d MMMM", ctx.locale())
        return when (date) {
            today -> ctx.getString(R.string.day_today)
            today.plusDays(1) -> ctx.getString(R.string.day_tomorrow, date.format(fmt))
            else -> date.format(fmt)
        }
    }

    fun describe(ctx: Context, day: DaySlots?): String =
        day?.let { Logic.formatDay(it) } ?: ctx.getString(R.string.no_outages)

    fun scheduleChanged(ctx: Context, region: RegionData, group: String, changes: List<Change>) {
        val days = region.days(group).associateBy { it.date }
        val lines = changes.map { c ->
            when (c) {
                is Change.NewDay -> ctx.getString(R.string.notif_day_new, dayLabel(ctx, c.date), describe(ctx, days[c.date]))
                is Change.DayChanged -> ctx.getString(R.string.notif_day_changed, dayLabel(ctx, c.date), describe(ctx, days[c.date]))
            }
        }
        val title = if (changes.all { it is Change.NewDay }) ctx.getString(R.string.notif_new_title, group)
        else ctx.getString(R.string.notif_changed_title, group)
        post(ctx, CH_CHANGES, ID_CHANGES, title, lines.joinToString("\n"))
    }

    fun emergency(ctx: Context, region: RegionData, on: Boolean) {
        val name = region.name(ctx.lang())
        if (on) post(ctx, CH_CHANGES, ID_EMERGENCY, ctx.getString(R.string.notif_emergency_on), ctx.getString(R.string.notif_emergency_on_body, name))
        else post(ctx, CH_CHANGES, ID_EMERGENCY, ctx.getString(R.string.notif_emergency_off), ctx.getString(R.string.notif_emergency_off_body, name))
    }

    fun reminder(ctx: Context, group: String, iv: Interval, minutes: Long) {
        val day = iv.start.toLocalDate()
        val title = if (minutes <= 1) ctx.getString(R.string.remind_now) else ctx.getString(R.string.remind_title, minutes)
        post(ctx, CH_REMIND, ID_REMIND, title, ctx.getString(R.string.remind_body, group, Logic.hm(iv.start, day), Logic.hm(iv.end, day)))
    }
}
