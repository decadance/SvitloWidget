package app.svitlograf

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/** Lays the real widget out at every launcher size and checks that what WidgetFit picks actually fits. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xxhdpi")
class WidgetFitTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val feed = Parser.parse(javaClass.getResource("/sample.json")!!.readText())
    private val odesa = feed.region("odeska-oblast")!!
    private val morning = LocalDateTime.of(2026, 11, 4, 9, 12)

    private fun withTomorrow(r: RegionData) = RegionData(r.id, r.nameUk, r.nameEn, r.emergency, r.groups.mapValues { (_, days) ->
        days + days.map { DaySlots(it.date.plusDays(1), it.off.reversedArray()) }
    })

    private class Measured(val heightDp: Float, val lines: Int, val listRows: Int, val pill: Boolean, val timeline: Boolean, val label: String)

    private fun measure(region: RegionData, hDp: Int, wDp: Int = 340, now: LocalDateTime = morning): Measured {
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        val root = Widget.build(ctx, Place(1, "", region.id, "2.1"), opts, now, region, SystemClock.elapsedRealtime())
            .apply(ctx, FrameLayout(ctx)) as ViewGroup
        val d = ctx.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((wDp * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val shown = root.findViewById<TextView>(R.id.intervals)
        val list = root.findViewById<ViewGroup>(R.id.upcoming)
        return Measured(
            root.measuredHeight / d,
            if (shown.visibility == View.VISIBLE) shown.text.split('\n').size else 0,
            if (list.visibility == View.VISIBLE) list.childCount else 0,
            root.findViewById<View>(R.id.pill).visibility == View.VISIBLE,
            root.findViewById<View>(R.id.timeline).visibility == View.VISIBLE,
            root.findViewById<TextView>(R.id.next_label).text.toString(),
        )
    }

    private fun sweep(region: RegionData, name: String) {
        // Below the header itself (~80dp) there's nothing left to drop.
        val minimum = measure(region, 40).heightDp
        for (w in listOf(180, 250, 340, 420)) for (h in 80..520 step 10) {
            val m = measure(region, h, w)
            println("%-15s font=%.2f w=%3d h=%3d used=%5.1f pill=%s tl=%s lines=%d list=%d".format(
                name, ctx.resources.configuration.fontScale, w, h, m.heightDp, m.pill, m.timeline, m.lines, m.listRows))
            if (h >= minimum) assertTrue("$name w=$w h=$h uses ${m.heightDp}dp", m.heightDp <= h + 0.5f)
        }
    }

    @Test fun fitsAtEverySize() {
        sweep(odesa, "today only")
        sweep(withTomorrow(odesa), "today+tomorrow")
        sweep(RegionData(odesa.id, odesa.nameUk, odesa.nameEn, true, odesa.groups), "emergency")
    }

    @Test fun fitsWithLargerFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        sweep(odesa, "today only")
        sweep(withTomorrow(odesa), "today+tomorrow")
    }

    @Test fun twoCellWidgetKeepsBothBarsAndTheTodayLine() {
        assertEquals(WidgetFit.Fit(2, true, false, 1), WidgetFit.choose(205, 340, 1f, listOf(1), 0, 0))
        val m = measure(odesa, 205)
        assertEquals(1, m.lines)
        assertEquals(0, m.listRows)
    }

    @Test fun tallWidgetShowsUpcomingOutagesInsteadOfEmptySpace() {
        val tall = measure(withTomorrow(odesa), 420)
        assertTrue("list rows: ${tall.listRows}", tall.listRows >= 3)
        assertEquals(0, tall.lines)
        // Whatever is left after the list makes the bars thicker, not blank space.
        val huge = WidgetFit.choose(600, 340, 1f, listOf(1, 1), 0, 4)
        assertEquals(4, huge.list)
        assertEquals(26f, huge.barDp)
        assertTrue(huge.hero)
    }

    @Test fun shortWidgetDropsTimelineThenPill() {
        val noTimeline = measure(odesa, 130)
        assertTrue(noTimeline.pill)
        assertTrue(!noTimeline.timeline)
        val mini = measure(odesa, 90)
        assertTrue(!mini.pill)
    }

    @Test fun narrowWidgetUsesShortCountdownLabel() {
        assertEquals("до 10:30 · ", measure(odesa, 260, 200).label)
        assertEquals("Увімкнуть о 10:30 · через ", measure(odesa, 260, 340).label)
    }
}
