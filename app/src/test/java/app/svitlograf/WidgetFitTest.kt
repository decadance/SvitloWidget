package app.svitlograf

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Lays the real widget out at every launcher height and checks that what WidgetFit picks actually fits. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi")
class WidgetFitTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val feed = Parser.parse(javaClass.getResource("/sample.json")!!.readText())
    private val odesa = feed.region("odeska-oblast")!!
    private val now = LocalDateTime.of(2026, 11, 4, 15, 12)

    private fun withTomorrow(r: RegionData) = RegionData(r.id, r.nameUk, r.nameEn, r.emergency, r.groups.mapValues { (_, days) ->
        days + days.map { DaySlots(it.date.plusDays(1), it.off.reversedArray()) }
    })

    /** Height of the widget content in px, and how many interval lines are shown. */
    private fun measure(region: RegionData, hDp: Int, wDp: Int = 340): Pair<Int, Int> {
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        val place = Place(1, "", region.id, "2.1")
        val root = Widget.build(ctx, place, opts, now, region, SystemClock.elapsedRealtime()).apply(ctx, FrameLayout(ctx)) as ViewGroup
        val d = ctx.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((wDp * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val shown = root.findViewById<android.widget.TextView>(R.id.intervals)
        val lines = if (shown.visibility == View.VISIBLE) shown.text.split('\n').size else 0
        return root.measuredHeight to lines
    }

    private fun sweep(region: RegionData, label: String) {
        val d = ctx.resources.displayMetrics.density
        // The smallest layout (status, countdown, one bar): below it there's nothing left to drop.
        val minimum = measure(region, 100).first / d
        for (h in 120..340 step 5) {
            val (px, lines) = measure(region, h)
            val usedDp = px / d
            println("%-22s font=%.2f h=%3d used=%5.1f lines=%d".format(label, ctx.resources.configuration.fontScale, h, usedDp, lines))
            if (h >= minimum) assertTrue("$label h=$h uses ${usedDp}dp", usedDp <= h + 0.5f)
        }
    }

    @Test fun fitsAtEveryHeight() {
        Prefs(ctx).updatePlace(Prefs(ctx).places.first().copy(region = "odeska-oblast", group = "2.1"))
        sweep(odesa, "today only")
        sweep(withTomorrow(odesa), "today+tomorrow")
        sweep(RegionData(odesa.id, odesa.nameUk, odesa.nameEn, true, odesa.groups), "emergency")
    }

    @Test fun fitsWithLargerFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        sweep(odesa, "today only")
        sweep(withTomorrow(odesa), "today+tomorrow")
    }

    @Test fun intervalsBeatTheTomorrowBar() {
        // A 2-row launcher cell on most phones is ~200-240dp: the interval line has to be there.
        for (h in listOf(195, 215, 230, 240)) assertEquals("h=$h", 1, measure(odesa, h).second)
        assertEquals(WidgetFit.Fit(1, true, 1), WidgetFit.choose(195, 1f, 1, false))
        assertEquals(WidgetFit.Fit(1, false, 1), WidgetFit.choose(215, 1f, 1, false))
        assertEquals(WidgetFit.Fit(2, false, 1), WidgetFit.choose(240, 1f, 2, false))
        assertEquals(WidgetFit.Fit(2, false, 2), WidgetFit.choose(300, 1f, 2, false))
        assertEquals(WidgetFit.Fit(1, true, 0), WidgetFit.choose(140, 1f, 2, false))
    }
}
