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
    private const val CH_UPDATES = "updates"
    private const val ID_CHANGES = 100
    private const val ID_REMIND = 200
    private const val ID_EMERGENCY = 300
    private const val ID_UPDATE = 400

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
        if (Updates.enabled) nm.createNotificationChannel(
            NotificationChannel(CH_UPDATES, ctx.getString(R.string.ch_updates), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, placeId: Int? = null) {
        if (!canPost(ctx)) return
        createChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { placeId?.let { putExtra(MainActivity.EXTRA_PLACE, it) } }
        val open = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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

    /** "Group 2.1", or "Home, Work · group 2.1" when the user follows several addresses. */
    fun label(ctx: Context, s: Schedules.Followed, multi: Boolean): String =
        if (!multi) ctx.getString(R.string.group_fmt, s.group)
        else s.places.joinToString(", ") { it.displayName(ctx) } + " · " + ctx.getString(R.string.group_lower, s.group)

    fun scheduleChanged(ctx: Context, s: Schedules.Followed, region: RegionData, changes: List<Change>, multi: Boolean) {
        val days = region.days(s.group).associateBy { it.date }
        val lines = changes.map { c ->
            when (c) {
                is Change.NewDay -> ctx.getString(R.string.notif_day_new, dayLabel(ctx, c.date), describe(ctx, days[c.date]))
                is Change.DayChanged -> ctx.getString(R.string.notif_day_changed, dayLabel(ctx, c.date), describe(ctx, days[c.date]))
            }
        }
        val first = s.places.minOf { it.id }
        val title = if (changes.all { it is Change.NewDay }) ctx.getString(R.string.notif_new_title, label(ctx, s, multi))
        else ctx.getString(R.string.notif_changed_title, label(ctx, s, multi))
        post(ctx, CH_CHANGES, ID_CHANGES + first, title, lines.joinToString("\n"), first)
    }

    fun emergency(ctx: Context, region: RegionData, on: Boolean) {
        val name = region.name(ctx.lang())
        val id = ID_EMERGENCY + Math.floorMod(region.id.hashCode(), 99)
        if (on) post(ctx, CH_CHANGES, id, ctx.getString(R.string.notif_emergency_on), ctx.getString(R.string.notif_emergency_on_body, name))
        else post(ctx, CH_CHANGES, id, ctx.getString(R.string.notif_emergency_off), ctx.getString(R.string.notif_emergency_off_body, name))
    }

    fun reminder(ctx: Context, s: Schedules.Followed, iv: Interval, minutes: Long, multi: Boolean) {
        val day = iv.start.toLocalDate()
        val first = s.places.minOf { it.id }
        val title = if (minutes <= 1) ctx.getString(R.string.remind_now) else ctx.getString(R.string.remind_title, minutes)
        val body = ctx.getString(R.string.remind_body, label(ctx, s, multi), Logic.hm(iv.start, day), Logic.hm(iv.end, day))
        post(ctx, CH_REMIND, ID_REMIND + first, title, body, first)
    }

    fun updateAvailable(ctx: Context, versionName: String) =
        post(ctx, CH_UPDATES, ID_UPDATE, ctx.getString(R.string.notif_update_title, versionName), ctx.getString(R.string.notif_update_body))
}
