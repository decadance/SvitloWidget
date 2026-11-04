package app.svitlograf

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.text.Collator
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val sp: SharedPreferences by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private var shownRegions: List<RegionData> = emptyList()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "fetched_at" || key == "last_error") runOnUiThread { refresh() }
    }

    private val minuteOptions = listOf(5, 10, 15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.createChannels(this)
        Sync.schedulePeriodic(this)
        if (Store.load(this) == null) Sync.now(this)

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        findViewById<MaterialAutoCompleteTextView>(R.id.region).setOnItemClickListener { _, _, pos, _ ->
            val r = shownRegions.getOrNull(pos) ?: return@setOnItemClickListener
            if (r.id == prefs.region) return@setOnItemClickListener
            prefs.region = r.id
            if (prefs.group !in r.groups.keys) prefs.group = null
            selectionChanged()
        }

        findViewById<MaterialSwitch>(R.id.sw_changes).apply {
            isChecked = prefs.notifyChanges
            setOnCheckedChangeListener { _, c -> prefs.notifyChanges = c }
        }
        findViewById<MaterialSwitch>(R.id.sw_before).apply {
            isChecked = prefs.notifyBefore
            setOnCheckedChangeListener { _, c -> prefs.notifyBefore = c; Alarms.reschedule(context) }
        }

        val minutes = findViewById<MaterialButtonToggleGroup>(R.id.minutes)
        minuteOptions.forEach { m -> minutes.addView(toggle(getString(R.string.minutes_fmt, m), m)) }
        minutes.check(minuteOptions.indexOf(prefs.minutesBefore).let { if (it < 0) 15 else prefs.minutesBefore })
        minutes.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || id == prefs.minutesBefore) return@addOnButtonCheckedListener
            prefs.minutesBefore = id
            prefs.lastRemindedStart = 0
            Alarms.reschedule(this)
        }

        val language = findViewById<MaterialButtonToggleGroup>(R.id.language)
        val langs = listOf("" to R.string.lang_system, "uk" to R.string.lang_uk, "en" to R.string.lang_en)
        langs.forEachIndexed { i, (_, label) -> language.addView(toggle(getString(label), 100 + i)) }
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
        language.check(100 + langs.indexOfFirst { it.first == current }.coerceAtLeast(0))
        language.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val tag = langs[id - 100].first
            if (tag == current) return@addOnButtonCheckedListener
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
            )
        }

        findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { Sync.now(this) }
        findViewById<MaterialButton>(R.id.btn_widget).setOnClickListener { pinWidget() }
        findViewById<MaterialButton>(R.id.btn_notif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { askBattery() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        sp.registerOnSharedPreferenceChangeListener(listener)
        // Language may have changed: refresh channel names and the home-screen widget.
        Notifier.createChannels(this)
        Widget.updateAll(this)
        refresh()
    }

    override fun onPause() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun selectionChanged() {
        prefs.lastRemindedStart = 0
        Store.reseed(this)
        Widget.updateAll(this)
        Alarms.reschedule(this)
        refresh()
    }

    private fun toggle(label: String, id: Int): MaterialButton =
        (layoutInflater.inflate(R.layout.item_toggle, null, false) as MaterialButton).apply {
            this.id = id
            text = label
        }

    fun refresh() {
        val feed = Store.load(this)
        val lang = lang()

        // Region dropdown
        val collator = Collator.getInstance(locale())
        shownRegions = feed?.regions.orEmpty().sortedWith(compareBy(collator) { it.name(lang) })
        val region = feed?.region(prefs.region)
        val regionView = findViewById<MaterialAutoCompleteTextView>(R.id.region)
        regionView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, shownRegions.map { it.name(lang) }))
        regionView.setText(region?.name(lang) ?: "", false)
        findViewById<View>(R.id.region_layout).isEnabled = feed != null
        if (feed == null) regionView.setText(getString(R.string.no_data_yet), false)

        // Group chips
        val chips = findViewById<ChipGroup>(R.id.groups)
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        region?.sortedGroups?.forEach { g ->
            val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = g
            chip.tag = g
            chip.isChecked = g == prefs.group
            chips.addView(chip)
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            val g = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String }
            if (g != prefs.group) { prefs.group = g; selectionChanged() }
        }

        // Widget preview, rendered from the same RemoteViews as the home-screen widget
        val preview = findViewById<FrameLayout>(R.id.preview)
        preview.removeAllViews()
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32).toInt()
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260)
        }
        // Plain app context: the AppCompat inflater would swap in views RemoteViews cannot drive.
        preview.addView(Widget.build(this, opts, region = region).apply(applicationContext, preview))

        // Permission card
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val restricted = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.btn_notif).visibility = if (needNotif) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_battery).visibility = if (restricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.perm_card).visibility = if (needNotif || restricted) View.VISIBLE else View.GONE

        // Footer
        val f = prefs.fetchedAt
        val time = if (f == 0L) getString(R.string.footer_never)
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(f), KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        val lines = mutableListOf(getString(R.string.footer_updated, time))
        prefs.lastError?.let { lines += getString(R.string.footer_error, it) }
        lines += getString(R.string.footer_source)
        findViewById<TextView>(R.id.footer).text = lines.joinToString("\n")
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(this, Widget::class.java), null, null)
        else Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show()
    }
}
