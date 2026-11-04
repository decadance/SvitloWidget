package app.svitlograf

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = if (Store.sync(applicationContext)) Result.success() else Result.retry()
}

object Sync {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(net)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun now(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(net)
            .apply {
                // Below API 31 expedited work needs a foreground notification; plain work is fine there.
                if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync_now", ExistingWorkPolicy.REPLACE, req)
    }
}

object Alarms {
    const val ACTION_TICK = "app.svitlograf.TICK"
    const val ACTION_REMIND = "app.svitlograf.REMIND"
    const val EXTRA_START = "start"

    private fun pi(ctx: Context, action: String, extra: Long = 0) = PendingIntent.getBroadcast(
        ctx, action.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_START, extra),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun at(ctx: Context, time: LocalDateTime, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = time.atZone(KYIV).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    fun reschedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val data = Store.selected(ctx)
        val group = prefs.group
        val now = LocalDateTime.now(KYIV)
        val snap = Snapshot.build(data, group, now)
        val am = ctx.getSystemService(AlarmManager::class.java)

        // Widget refresh at the next on/off switch or midnight, whichever is first.
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(5)
        val tick = listOfNotNull(snap.nextChange?.plusSeconds(1), midnight).min()
        at(ctx, tick, pi(ctx, ACTION_TICK))

        val remind = pi(ctx, ACTION_REMIND)
        am.cancel(remind)
        if (!prefs.notifyBefore || data == null || group == null || data.emergency) return
        val lead = prefs.minutesBefore.toLong()
        val next = Logic.intervals(data.days(group)).firstOrNull { it.start.isAfter(now) } ?: return
        val startMs = next.start.atZone(KYIV).toInstant().toEpochMilli()
        if (prefs.lastRemindedStart == startMs) return
        val fireAt = next.start.minusMinutes(lead).let { if (it.isBefore(now)) now.plusSeconds(2) else it }
        at(ctx, fireAt, pi(ctx, ACTION_REMIND, startMs))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Alarms.ACTION_REMIND -> {
                val prefs = Prefs(ctx)
                val startMs = intent.getLongExtra(Alarms.EXTRA_START, 0)
                val data = Store.selected(ctx)
                val group = prefs.group
                val iv = if (data == null || group == null) null else data.let { d ->
                    Logic.intervals(d.days(group))
                        .firstOrNull { it.start.atZone(KYIV).toInstant().toEpochMilli() == startMs }
                }
                // Only remind if the outage is still in the current schedule.
                if (iv != null && prefs.notifyBefore && prefs.lastRemindedStart != startMs) {
                    val mins = Duration.between(LocalDateTime.now(KYIV), iv.start).toMinutes().coerceAtLeast(0) + 1
                    Notifier.reminder(ctx, group!!, iv, mins.coerceAtMost(prefs.minutesBefore.toLong()))
                    prefs.lastRemindedStart = startMs
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Sync.schedulePeriodic(ctx)
                Sync.now(ctx)
            }
        }
        Widget.updateAll(ctx)
        Alarms.reschedule(ctx)
    }
}
