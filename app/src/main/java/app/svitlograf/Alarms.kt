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
    override fun doWork(): Result {
        val ok = Store.sync(applicationContext)
        Updates.checkInBackground(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
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
    private const val EXTRA_START = "start"
    private const val EXTRA_KEY = "key"
    private const val REMIND_BASE = 10_000

    private fun pi(ctx: Context, code: Int, action: String, key: String? = null, start: Long = 0) = PendingIntent.getBroadcast(
        ctx, code,
        Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_KEY, key).putExtra(EXTRA_START, start),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun at(ctx: Context, time: LocalDateTime, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = time.atZone(KYIV).toInstant().toEpochMilli()
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    fun reschedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val feed = Store.load(ctx)
        val now = LocalDateTime.now(KYIV)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val places = prefs.places

        // Widget refresh at the earliest on/off switch of any place, or at midnight.
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(5)
        val switches = places.mapNotNull { Snapshot.build(feed?.region(it.region), it.group, now).nextChange?.plusSeconds(1) }
        at(ctx, (switches + midnight).min(), pi(ctx, 1, ACTION_TICK))

        // One reminder alarm per followed schedule, keyed by the smallest place id that follows it.
        val active = mutableSetOf<Int>()
        for (s in Schedules.followed(places) { it.notifyBefore }) {
            val code = REMIND_BASE + s.places.minOf { it.id }
            val region = feed?.region(s.region) ?: continue
            if (region.emergency) continue
            val next = Logic.intervals(region.days(s.group)).firstOrNull { it.start.isAfter(now) } ?: continue
            val startMs = next.start.atZone(KYIV).toInstant().toEpochMilli()
            if (prefs.lastReminded(s.key) == startMs) continue
            val fireAt = next.start.minusMinutes(prefs.minutesBefore.toLong()).let { if (it.isBefore(now)) now.plusSeconds(2) else it }
            at(ctx, fireAt, pi(ctx, code, ACTION_REMIND, s.key, startMs))
            active += code
        }
        (prefs.reminderCodes - active).forEach { am.cancel(pi(ctx, it, ACTION_REMIND)) }
        prefs.reminderCodes = active
    }

    fun onRemind(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val startMs = intent.getLongExtra(EXTRA_START, 0)
        val places = prefs.places
        val s = Schedules.followed(places) { it.notifyBefore }.firstOrNull { it.key == key } ?: return
        if (prefs.lastReminded(key) == startMs) return
        // Only remind if the outage is still in the current schedule.
        val iv = Store.load(ctx)?.region(s.region)?.let { r -> Logic.intervals(r.days(s.group)) }
            ?.firstOrNull { it.start.atZone(KYIV).toInstant().toEpochMilli() == startMs } ?: return
        val mins = Duration.between(LocalDateTime.now(KYIV), iv.start).toMinutes().coerceAtLeast(0) + 1
        Notifier.reminder(ctx, s, iv, mins.coerceAtMost(prefs.minutesBefore.toLong()), places.size > 1)
        prefs.setLastReminded(key, startMs)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Alarms.ACTION_REMIND -> Alarms.onRemind(ctx, intent)
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
