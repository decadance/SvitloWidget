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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/** The widget at typical launcher sizes, for visual review (build/renders/gallery_*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GalleryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val out = File(System.getProperty("user.dir"), "build/renders").apply { mkdirs() }
    private val base = Parser.parse(javaClass.getResource("/sample.json")!!.readText()).region("odeska-oblast")!!
    private val region = RegionData(base.id, base.nameUk, base.nameEn, false, base.groups.mapValues { (_, days) ->
        days + days.map { DaySlots(it.date.plusDays(1), it.off.reversedArray()) }
    })

    private fun render(name: String, w: Int, h: Int, wallpaper: Int) {
        Prefs(ctx).fetchedAt = System.currentTimeMillis() - 3 * 60_000
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        }
        val v = Widget.build(ctx, Place(1, "", region.id, "2.1"), opts, LocalDateTime.of(2026, 11, 4, 13, 10), region, SystemClock.elapsedRealtime())
            .apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        val wPx = (w * d).toInt(); val hPx = (h * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, wPx, hPx)
        val bmp = Bitmap.createBitmap(wPx + 48, hPx + 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(wallpaper)
        c.translate(24f, 24f)
        v.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val sizes = listOf(180 to 100, 180 to 210, 250 to 140, 340 to 205, 340 to 320, 340 to 430, 420 to 520)

    @Test @Config(qualifiers = "uk-w411dp-h891dp-notnight-xxhdpi") fun lightUk() =
        sizes.forEach { (w, h) -> render("gallery_light_${w}x$h", w, h, 0xFFB9C7DE.toInt()) }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-night-xxhdpi") fun darkUk() =
        sizes.forEach { (w, h) -> render("gallery_dark_${w}x$h", w, h, 0xFF20242E.toInt()) }
}
