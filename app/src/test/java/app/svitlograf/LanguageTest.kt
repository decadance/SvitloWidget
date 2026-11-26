package app.svitlograf

import android.app.LocaleManager
import android.content.Context
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** System in English, app set to Ukrainian: the widget built from a background context must stay Ukrainian. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class LanguageTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val region = Parser.parse(javaClass.getResource("/sample.json")!!.readText()).region("odeska-oblast")!!

    private fun status(): String {
        val rv = Widget.build(ctx, Place(1, "", region.id, "2.1"), Bundle(), LocalDateTime.of(2026, 11, 4, 15, 0), region, SystemClock.elapsedRealtime())
        return rv.apply(ctx, FrameLayout(ctx)).findViewById<TextView>(R.id.status).text.toString()
    }

    @Test @Config(sdk = [34]) fun widgetFollowsAppLanguageOnAndroid13Plus() {
        assertEquals("Power is off", status())
        ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("uk")
        assertEquals("Світла немає", status())
        assertEquals("Світла немає", ctx.localized().getString(R.string.power_off))
    }

    @Test @Config(sdk = [30]) fun widgetFollowsAppLanguageBeforeAndroid13() {
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putString("language", "uk").commit()
        assertEquals("Світла немає", status())
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putString("language", "").commit()
        assertEquals("Power is off", status())
    }
}
