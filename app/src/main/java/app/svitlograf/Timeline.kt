package app.svitlograf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDateTime

/**
 * The timeline is drawn as alpha-only masks, one per colour role. The widget layout tints each
 * mask from theme resources, so the launcher resolves light/dark/dynamic colours itself and the
 * bitmap never has to be re-rendered when the system theme flips.
 */
class TimelineLayers(val track: Bitmap, val on: Bitmap, val off: Bitmap, val text: Bitmap, val marker: Bitmap)

object Timeline {
    class Labels(val today: String, val tomorrow: String, val noSchedule: String)

    fun render(
        widthPx: Int, density: Float, today: DaySlots?, tomorrow: DaySlots?, now: LocalDateTime,
        showTomorrow: Boolean, compact: Boolean, labels: Labels,
    ): TimelineLayers {
        val d = density
        val labelW = if (compact) 0f else 72 * d
        val barH = 16 * d
        val rowGap = 10 * d
        val scaleH = if (compact) 0f else 16 * d
        val pad = 5 * d
        val rows = if (showTomorrow) 2 else 1
        val w = widthPx.coerceAtLeast(16)
        val h = (pad + rows * barH + (rows - 1) * rowGap + pad + scaleH).toInt()

        fun mask() = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val L = TimelineLayers(mask(), mask(), mask(), mask(), mask())
        val cTrack = Canvas(L.track); val cOn = Canvas(L.on); val cOff = Canvas(L.off)
        val cText = Canvas(L.text); val cMarker = Canvas(L.marker)

        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 255 }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val left = labelW
        val right = w - 1 * d
        val span = right - left
        val gap = 3 * d
        val r = barH / 2
        val nowX = left + span * (now.hour * 60 + now.minute) / (24f * 60)

        fun x(slot: Int) = left + span * slot / SLOTS

        fun row(top: Float, day: DaySlots?, name: String, isToday: Boolean) {
            if (!compact) cText.drawText(name, 0f, top + barH / 2 + text.textSize * 0.35f, text)
            if (day == null) {
                cTrack.drawRoundRect(RectF(left, top, right, top + barH), r, r, solid)
                val t = Paint(text).apply { textSize = 11 * d; textAlign = Paint.Align.CENTER; alpha = 200 }
                cText.drawText(labels.noSchedule, (left + right) / 2, top + barH / 2 + t.textSize * 0.35f, t)
                return
            }
            var i = 0
            while (i < SLOTS) {
                var j = i
                while (j < SLOTS && day.off[j] == day.off[i]) j++
                val s = x(i) + if (i == 0) 0f else gap / 2
                val e = x(j) - if (j == SLOTS) 0f else gap / 2
                if (e - s > 0.5f) {
                    val c = if (day.off[i]) cOff else cOn
                    c.drawRoundRect(RectF(s, top, e, top + barH), minOf(r, (e - s) / 2), minOf(r, (e - s) / 2), solid)
                }
                i = j
            }
            if (isToday) {
                // Dim what is already past, and cut a notch around the "now" marker.
                val dim = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); alpha = 95 }
                val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                for (c in listOf(cOn, cOff)) {
                    c.drawRect(left, top, nowX, top + barH, dim)
                    c.drawRect(nowX - 3.5f * d, top - pad, nowX + 3.5f * d, top + barH + pad, clear)
                }
                cMarker.drawRoundRect(RectF(nowX - 1.75f * d, top - pad + d, nowX + 1.75f * d, top + barH + pad - d), 2 * d, 2 * d, solid)
            }
        }

        var top = pad
        row(top, today, labels.today, true)
        if (showTomorrow) {
            top += barH + rowGap
            row(top, tomorrow, labels.tomorrow, false)
        }
        if (!compact) {
            val scale = Paint(text).apply { textSize = 10.5f * d; alpha = 170; typeface = Typeface.DEFAULT }
            val baseY = top + barH + pad + scale.textSize
            for (hh in listOf(0, 6, 12, 18, 24)) {
                scale.textAlign = when (hh) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                cText.drawText("%02d".format(hh), left + span * hh / 24f, baseY, scale)
            }
        }
        return L
    }
}
