package app.svitlograf

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.view.inputmethod.EditorInfo
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.Collator
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val sp: SharedPreferences by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private var shownRegions: List<RegionData> = emptyList()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("fetched_at", "last_error", "update_available")) runOnUiThread { refresh() }
    }

    private val minuteOptions = listOf(5, 10, 15, 30, 60)
    private var downloading = false
    private var installWhenAllowed = false

    /** The address being edited; always one of prefs.places. */
    private val place: Place
        get() = prefs.place(prefs.selectedPlace) ?: prefs.places.first().also { prefs.selectedPlace = it.id }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.createChannels(this)
        Sync.schedulePeriodic(this)
        if (Store.load(this) == null) Sync.now(this)
        selectFromIntent(intent)

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        findViewById<MaterialAutoCompleteTextView>(R.id.region).setOnItemClickListener { _, _, pos, _ ->
            val r = shownRegions.getOrNull(pos) ?: return@setOnItemClickListener
            val p = place
            if (r.id == p.region) return@setOnItemClickListener
            placeChanged(p.copy(region = r.id, group = p.group?.takeIf { it in r.groups.keys }))
        }

        findViewById<TextInputEditText>(R.id.name).apply {
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveName(v.text.toString()); v.clearFocus() }
                false
            }
            setOnFocusChangeListener { v, has -> if (!has) saveName((v as TextView).text.toString()) }
        }

        // refresh() sets these to the saved values, so only real changes get past the comparison.
        findViewById<MaterialSwitch>(R.id.sw_changes).setOnCheckedChangeListener { _, c ->
            if (c != place.notifyChanges) prefs.updatePlace(place.copy(notifyChanges = c))
        }
        findViewById<MaterialSwitch>(R.id.sw_before).setOnCheckedChangeListener { _, c ->
            if (c != place.notifyBefore) { prefs.updatePlace(place.copy(notifyBefore = c)); Alarms.reschedule(this); refresh() }
        }

        val minutes = findViewById<MaterialButtonToggleGroup>(R.id.minutes)
        minuteOptions.forEach { m -> minutes.addView(toggle(getString(R.string.minutes_fmt, m), m)) }
        minutes.check(if (prefs.minutesBefore in minuteOptions) prefs.minutesBefore else 15)
        minutes.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || id == prefs.minutesBefore) return@addOnButtonCheckedListener
            prefs.minutesBefore = id
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
        findViewById<MaterialButton>(R.id.btn_delete).setOnClickListener { confirmDelete() }
        findViewById<MaterialButton>(R.id.btn_notif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<MaterialButton>(R.id.btn_exact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { askBattery() }
        findViewById<MaterialButton>(R.id.btn_privacy).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
        }
        findViewById<MaterialButton>(R.id.btn_check_updates).apply {
            visibility = if (Updates.enabled) View.VISIBLE else View.GONE
            setOnClickListener { checkUpdates(manual = true) }
        }
        findViewById<MaterialButton>(R.id.update_button).setOnClickListener { startUpdate() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        selectFromIntent(intent)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        sp.registerOnSharedPreferenceChangeListener(listener)
        // Language may have changed: refresh channel names and the home-screen widgets.
        Notifier.createChannels(this)
        Widget.updateAll(this)
        Alarms.reschedule(this)
        refresh()
        if (Updates.dueForCheck(this)) checkUpdates(manual = false)
        if (installWhenAllowed && Installer.canInstall(this)) { installWhenAllowed = false; startUpdate() }
    }

    override fun onPause() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun selectFromIntent(intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_PLACE, -1) ?: -1
        if (id > 0 && prefs.place(id) != null) prefs.selectedPlace = id
    }

    private fun placeChanged(p: Place) {
        prefs.updatePlace(p)
        Store.reseed(this, p)
        Widget.updateAll(this)
        Alarms.reschedule(this)
        refresh()
    }

    private fun saveName(raw: String) {
        val name = raw.trim()
        if (name != place.name) placeChanged(place.copy(name = name))
    }

    private fun addPlace() {
        if (prefs.places.size >= Place.MAX) return
        val p = prefs.addPlace()
        prefs.selectedPlace = p.id
        refresh()
        findViewById<TextInputEditText>(R.id.name).requestFocus()
    }

    private fun confirmDelete() {
        val p = place
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_place_confirm, p.displayName(this)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                prefs.deletePlace(p.id)
                Widget.updateAll(this)
                Alarms.reschedule(this)
                refresh()
            }
            .show()
    }

    private fun toggle(label: String, id: Int): MaterialButton =
        (layoutInflater.inflate(R.layout.item_toggle, null, false) as MaterialButton).apply {
            this.id = id
            text = label
        }

    fun refresh() {
        val feed = Store.load(this)
        val lang = lang()
        val places = prefs.places
        val p = place

        // Address chips + "add"
        val chips = findViewById<ChipGroup>(R.id.places)
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        places.forEach { pl ->
            val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = pl.displayName(this)
            chip.tag = pl.id
            chip.isChecked = pl.id == p.id
            chips.addView(chip)
        }
        if (places.size < Place.MAX) {
            val add = layoutInflater.inflate(R.layout.item_chip_add, chips, false) as Chip
            add.setOnClickListener { addPlace() }
            chips.addView(add)
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            val id = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as? Int } ?: return@setOnCheckedStateChangeListener
            if (id != prefs.selectedPlace) {
                currentFocus?.clearFocus()
                prefs.selectedPlace = id
                refresh()
            }
        }

        findViewById<TextView>(R.id.place_section).text =
            if (places.size > 1) p.displayName(this) else getString(R.string.section_location)
        val nameView = findViewById<TextInputEditText>(R.id.name)
        if (!nameView.hasFocus()) nameView.setText(p.name)

        // Region dropdown
        val collator = Collator.getInstance(locale())
        shownRegions = feed?.regions.orEmpty().sortedWith(compareBy(collator) { it.name(lang) })
        val region = feed?.region(p.region)
        val regionView = findViewById<MaterialAutoCompleteTextView>(R.id.region)
        regionView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, shownRegions.map { it.name(lang) }))
        regionView.setText(region?.name(lang) ?: "", false)
        findViewById<View>(R.id.region_layout).isEnabled = feed != null
        if (feed == null) regionView.setText(getString(R.string.no_data_yet), false)

        // Group chips
        val groups = findViewById<ChipGroup>(R.id.groups)
        groups.setOnCheckedStateChangeListener(null)
        groups.removeAllViews()
        region?.sortedGroups?.forEach { g ->
            val chip = layoutInflater.inflate(R.layout.item_chip, groups, false) as Chip
            chip.id = View.generateViewId()
            chip.text = g
            chip.tag = g
            chip.isChecked = g == p.group
            groups.addView(chip)
        }
        groups.setOnCheckedStateChangeListener { group, ids ->
            val g = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String }
            if (g != place.group) placeChanged(place.copy(group = g))
        }

        findViewById<MaterialSwitch>(R.id.sw_changes).isChecked = p.notifyChanges
        findViewById<MaterialSwitch>(R.id.sw_before).isChecked = p.notifyBefore
        findViewById<View>(R.id.btn_delete).visibility = if (places.size > 1) View.VISIBLE else View.INVISIBLE

        // Widget preview, rendered from the same RemoteViews as the home-screen widget
        val preview = findViewById<FrameLayout>(R.id.preview)
        preview.removeAllViews()
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32).toInt()
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260)
        }
        // Plain app context: the AppCompat inflater would swap in views RemoteViews cannot drive.
        preview.addView(Widget.build(this, p, opts, region = region).apply(applicationContext, preview))

        // Permission card
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val needExact = places.any { it.notifyBefore } && !Alarms.canExact(this)
        val restricted = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.btn_notif).visibility = if (needNotif) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_exact).visibility = if (needExact) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_battery).visibility = if (restricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.perm_card).visibility = if (needNotif || needExact || restricted) View.VISIBLE else View.GONE

        refreshUpdateCard()

        // Footer
        val f = prefs.fetchedAt
        val time = if (f == 0L) getString(R.string.footer_never)
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(f), KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        val lines = mutableListOf(getString(R.string.footer_updated, time))
        prefs.lastError?.let { lines += getString(R.string.footer_error, it) }
        lines += getString(R.string.footer_source)
        lines += getString(R.string.footer_version, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.footer).text = lines.joinToString("\n")
    }

    // --- updates (GitHub build) ---

    private fun refreshUpdateCard() {
        val card = findViewById<View>(R.id.update_card)
        val r = if (Updates.enabled) Updates.available(this) else null
        if (r == null) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.update_title).text = getString(R.string.update_title, r.versionName)
        val notes = findViewById<TextView>(R.id.update_notes)
        notes.text = r.notes(lang()).orEmpty()
        notes.visibility = if (notes.text.isNullOrBlank()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.update_button).isEnabled = !downloading
    }

    private fun checkUpdates(manual: Boolean) {
        thread {
            val r = Updates.check(this)
            runOnUiThread {
                refreshUpdateCard()
                if (manual && r == null) Toast.makeText(this, R.string.up_to_date, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startUpdate() {
        val r = Updates.available(this) ?: return
        if (downloading) return
        if (!Installer.canInstall(this)) {
            installWhenAllowed = true
            Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show()
            Installer.openInstallPermission(this)
            return
        }
        downloading = true
        val bar = findViewById<LinearProgressIndicator>(R.id.update_progress)
        val button = findViewById<MaterialButton>(R.id.update_button)
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = false
        button.isEnabled = false
        thread {
            val result = runCatching {
                Updates.download(this, r) { share ->
                    runOnUiThread {
                        bar.setProgressCompat((share * 100).toInt(), true)
                        button.text = getString(R.string.update_downloading, (share * 100).toInt())
                    }
                }
            }
            runOnUiThread {
                downloading = false
                bar.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.update_install)
                result.onSuccess { Installer.install(this, it) }
                result.onFailure { e ->
                    Toast.makeText(this, getString(R.string.update_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- system settings ---

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        // The direct exemption dialog is only allowed outside Google Play; the Play build opens the system list.
        if (BuildConfig.DIRECT_BATTERY_REQUEST) {
            val ok = runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.isSuccess
            if (ok) return
        }
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (!mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show()
            return
        }
        // The system adds the new widget id to this broadcast, so it must be mutable.
        val callback = android.app.PendingIntent.getBroadcast(
            this, place.id,
            Intent(this, Widget::class.java).setAction(Widget.ACTION_PINNED).putExtra(Widget.EXTRA_PLACE, place.id),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mgr.requestPinAppWidget(ComponentName(this, Widget::class.java), null, callback)
    }

    companion object {
        const val EXTRA_PLACE = "place"
        const val PRIVACY_URL = "https://beta.decadance.me/svitlograf/privacy.html"
    }
}
