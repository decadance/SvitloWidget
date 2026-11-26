package app.svitlograf

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The language picked in the app ("uk", "en", or "" for the system one).
 *
 * Activities get it from AppCompat, but the widget and notifications are built from broadcast
 * receivers and workers, where the per-app language isn't reliably applied (the widget switched to
 * the system language after a resize). Background code therefore localizes its context explicitly.
 */
object AppLanguage {
    private const val KEY = "language"

    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system keeps the per-app language from Android 13 on; it is also changed from system Settings.
            val list = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (list == null || list.isEmpty) "" else list[0].language
        }
        return ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(ctx: Context, tag: String) {
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }
}

/** This context with the app's language applied; returns itself when it already matches. */
fun Context.localized(): Context {
    val lang = AppLanguage.get(this)
    if (lang.isEmpty() || resources.configuration.locales[0].language == lang) return this
    val cfg = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
    return createConfigurationContext(cfg)
}
