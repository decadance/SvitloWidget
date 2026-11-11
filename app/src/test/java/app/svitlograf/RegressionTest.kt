package app.svitlograf

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Bugs fixed in 1.1.3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class RegressionTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val sample = javaClass.getResource("/sample.json")!!.readText()

    @Test fun pickingAScheduleAgainDoesNotAnnounceItAsNew() {
        val today = LocalDateTime.now(KYIV).toLocalDate()
        File(ctx.filesDir, "feed.json").writeText(sample.replace("2026-11-04", today.toString()))
        val prefs = Prefs(ctx)
        val place = prefs.places.first().copy(region = "odeska-oblast", group = "2.1")
        prefs.updatePlace(place)
        // Followed a few days ago, then switched away: only old days are remembered.
        prefs.setSeen("odeska-oblast", "2.1", mapOf(today.minusDays(3) to "0".repeat(SLOTS)))

        Store.reseed(ctx, place)

        val region = Store.load(ctx)!!.region("odeska-oblast")!!
        assertTrue(region.days("2.1").any { it.date == today })
        assertEquals(emptyList<Change>(), Diff.changes(prefs.seen("odeska-oblast", "2.1")!!, region, "2.1", today))
    }

    @Test fun unreadableAddressListStartsOverInsteadOfCrashing() {
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putString("places", "{broken").commit()
        val prefs = Prefs(ctx)
        assertEquals(1, prefs.places.size)
        assertFalse(prefs.places.single().configured)
        assertEquals(prefs.places.single().id, prefs.selectedPlace)
    }

    @Test fun countdownSurvivesTheClockChange() {
        // 25 Oct 2026, 04:00 -> 03:00 in Kyiv: 01:00 to 05:00 is five real hours.
        val ms = kyivMillisBetween(LocalDateTime.of(2026, 10, 25, 1, 0), LocalDateTime.of(2026, 10, 25, 5, 0))
        assertEquals(5 * 3_600_000L, ms)
        assertEquals(3_600_000L, kyivMillisBetween(LocalDateTime.of(2026, 11, 4, 13, 0), LocalDateTime.of(2026, 11, 4, 14, 0)))
    }

    @Test fun pinCallbackDoesNotOverrideTheConfigScreenChoice() {
        val prefs = Prefs(ctx)
        val work = prefs.addPlace("Work")
        prefs.bindWidget(7, work.id)
        fun pinned(widgetId: Int, placeId: Int) = Widget().onReceive(
            ctx,
            Intent(ctx, Widget::class.java).setAction(Widget.ACTION_PINNED)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra(Widget.EXTRA_PLACE, placeId),
        )
        pinned(7, 1)
        assertEquals(work.id, prefs.placeForWidget(7)?.id)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val fresh = org.robolectric.Shadows.shadowOf(AppWidgetManager.getInstance(ctx)).createWidget(Widget::class.java, R.layout.widget)
        assertFalse(prefs.isBound(fresh))
        pinned(fresh, work.id)
        assertEquals(work.id, prefs.placeForWidget(fresh)?.id)
        assertTrue(prefs.isBound(fresh))
    }

    @Test fun regionWithoutTodaysScheduleSaysSoInsteadOfAskingToRefresh() {
        val region = Parser.parse(sample).region("odeska-oblast")!!
        val prefs = Prefs(ctx)
        fun label(): String {
            val rv = Widget.build(ctx, Place(1, "", region.id, "2.1"), Bundle(), LocalDateTime.of(2026, 12, 1, 12, 0), region, SystemClock.elapsedRealtime())
            return rv.apply(ctx, FrameLayout(ctx)).findViewById<TextView>(R.id.next_label).text.toString()
        }
        prefs.fetchedAt = System.currentTimeMillis()
        assertEquals("No schedule published for today", label())
        prefs.fetchedAt = System.currentTimeMillis() - 2 * 3_600_000
        assertEquals("No data for today — tap ↻", label())
    }
}
