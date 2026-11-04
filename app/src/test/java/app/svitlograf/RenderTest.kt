package app.svitlograf

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/** Renders the widget and the app to PNGs in build/renders for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val out = File(System.getProperty("user.dir"), "build/renders").apply { mkdirs() }
    private val json = javaClass.getResource("/sample.json")!!.readText()
    private val feed = Parser.parse(json)
    private val odesa = feed.region("odeska-oblast")!!

    @Before fun setup() {
        File(ctx.filesDir, "feed.json").writeText(json)
        Prefs(ctx).apply { region = "odeska-oblast"; group = "2.1"; fetchedAt = System.currentTimeMillis() - 3 * 60_000 }
    }

    private fun save(view: View, wPx: Int, hPx: Int, name: String, wallpaper: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, wPx, hPx)
        val pad = 48
        val bmp = Bitmap.createBitmap(wPx + 2 * pad, hPx + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(wallpaper)
        c.translate(pad.toFloat(), pad.toFloat())
        view.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun widget(name: String, now: LocalDateTime, wDp: Int, hDp: Int, region: RegionData? = odesa, group: String? = "2.1", dark: Boolean) {
        Prefs(ctx).group = group
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        val v = Widget.build(ctx, opts, now, region, SystemClock.elapsedRealtime()).apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        save(v, (wDp * d).toInt(), (hDp * d).toInt(), name, if (dark) 0xFF20242E.toInt() else 0xFFB9C7DE.toInt())
    }

    private fun states(prefix: String, dark: Boolean) {
        widget("${prefix}_1_on_4x3", LocalDateTime.of(2026, 11, 4, 13, 22), 360, 260, dark = dark)
        widget("${prefix}_2_off_4x3", LocalDateTime.of(2026, 11, 4, 15, 12), 360, 260, dark = dark)
        widget("${prefix}_3_medium", LocalDateTime.of(2026, 11, 4, 11, 5), 360, 215, dark = dark)
        widget("${prefix}_4_compact", LocalDateTime.of(2026, 11, 4, 11, 5), 330, 170, dark = dark)
        val withTomorrow = RegionData("odeska-oblast", "Одеська область", "Odesa Region", false, odesa.groups.mapValues { (_, days) ->
            days + days.map { DaySlots(it.date.plusDays(1), it.off.reversedArray()) }
        })
        widget("${prefix}_5_tomorrow", LocalDateTime.of(2026, 11, 4, 20, 40), 360, 260, withTomorrow, "5.1", dark)
        widget("${prefix}_6_setup", LocalDateTime.of(2026, 11, 4, 12, 0), 360, 215, null, null, dark)
        widget("${prefix}_7_emergency", LocalDateTime.of(2026, 11, 4, 12, 0), 360, 260,
            RegionData("odeska-oblast", "", "", true, odesa.groups), "2.1", dark)
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-notnight-xxhdpi") fun widgetLightUk() = states("light_uk", false)
    @Test @Config(qualifiers = "uk-w411dp-h891dp-night-xxhdpi") fun widgetDarkUk() = states("dark_uk", true)
    @Test @Config(qualifiers = "en-w411dp-h891dp-notnight-xxhdpi") fun widgetLightEn() = states("light_en", false)
    @Test @Config(qualifiers = "en-w411dp-h891dp-night-xxhdpi") fun widgetDarkEn() = states("dark_en", true)

    private fun app(name: String) {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = act.window.decorView
        val w = ctx.resources.displayMetrics.widthPixels
        val content = act.findViewById<View>(R.id.scroll)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = (content.measuredHeight + 160 * ctx.resources.displayMetrics.density).toInt().coerceAtMost(9000)
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-notnight-xxhdpi") fun appLightUk() = app("app_light_uk")
    @Test @Config(qualifiers = "uk-w411dp-h891dp-night-xxhdpi") fun appDarkUk() = app("app_dark_uk")
    @Test @Config(qualifiers = "en-w411dp-h891dp-night-xxhdpi") fun appDarkEn() = app("app_dark_en")
}
