package app.svitlograf

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import java.time.LocalDateTime

/** Shown when a widget is added or reconfigured: pick which address it shows. */
class WidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, result())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContentView(R.layout.activity_widget_config)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.config_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val prefs = Prefs(this)
        val places = prefs.places
        val feed = Store.load(this)
        val now = LocalDateTime.now(KYIV)
        val current = if (prefs.isBound(widgetId)) prefs.placeForWidget(widgetId)?.id else null
        val container = findViewById<LinearLayout>(R.id.options)

        places.forEachIndexed { i, p ->
            val card = layoutInflater.inflate(R.layout.item_place_option, container, false) as MaterialCardView
            card.shapeAppearanceModel = segment(i, places.size)
            card.isCheckable = true
            card.isChecked = p.id == current
            card.findViewById<TextView>(R.id.option_title).text = p.displayName(this)
            val region = feed?.region(p.region)
            card.findViewById<TextView>(R.id.option_sub).text =
                if (region != null && p.group != null) "${region.name(lang())} · ${getString(R.string.group_lower, p.group)}"
                else getString(R.string.place_not_set)
            val power = Snapshot.build(region, p.group, now).power
            card.findViewById<ImageView>(R.id.option_badge).setImageResource(
                when {
                    !p.configured -> R.drawable.badge_unknown
                    power == Power.ON -> R.drawable.badge_on
                    power == Power.OFF -> R.drawable.badge_off
                    else -> R.drawable.badge_unknown
                },
            )
            card.setOnClickListener { choose(p, openApp = !p.configured) }
            container.addView(card)
        }

        findViewById<MaterialButton>(R.id.btn_new).apply {
            isEnabled = places.size < Place.MAX
            setOnClickListener {
                // A fresh install has one empty address: use it instead of adding a second.
                val p = places.singleOrNull()?.takeIf { !it.configured } ?: prefs.addPlace()
                choose(p, openApp = true)
            }
        }
    }

    private fun choose(p: Place, openApp: Boolean) {
        val prefs = Prefs(this)
        prefs.bindWidget(widgetId, p.id)
        Widget.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, result())
        if (openApp) {
            prefs.selectedPlace = p.id
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PLACE, p.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun segment(i: Int, n: Int): ShapeAppearanceModel {
        val d = resources.displayMetrics.density
        val big = 28 * d
        val small = 6 * d
        val top = if (i == 0) big else small
        val bottom = if (i == n - 1) big else small
        return ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top).setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
            .build()
    }
}
