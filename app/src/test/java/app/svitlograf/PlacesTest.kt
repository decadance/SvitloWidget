package app.svitlograf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    @Test fun migratesSingleAddressFrom10() {
        sp().edit()
            .putString("region", "odeska-oblast").putString("group", "2.1")
            .putBoolean("notify_changes", false).putBoolean("notify_before", true)
            .putBoolean("emergency_seen", true).putLong("last_reminded", 123L)
            .putString("seen_odeska-oblast_2.1", "{}")
            .commit()
        val prefs = Prefs(ctx)
        assertEquals(listOf(Place(1, "", "odeska-oblast", "2.1", notifyChanges = false, notifyBefore = true)), prefs.places)
        assertTrue(prefs.emergencySeen("odeska-oblast"))
        assertEquals(123L, prefs.lastReminded("odeska-oblast_2.1"))
        assertFalse(sp().contains("region"))
        // Already announced schedules survive, so updating doesn't re-notify.
        assertTrue(prefs.seen("odeska-oblast", "2.1") != null)
        // Unbound widgets placed with 1.0 show the migrated address.
        assertEquals(1, prefs.placeForWidget(42)?.id)
    }

    @Test fun freshInstallHasOneEmptyAddress() {
        val p = Prefs(ctx).places.single()
        assertFalse(p.configured)
        assertNull(p.scheduleKey)
    }

    @Test fun widgetsBindToAddresses() {
        val prefs = Prefs(ctx)
        val work = prefs.addPlace("Work")
        prefs.bindWidget(10, 1)
        prefs.bindWidget(11, work.id)
        assertEquals(1, prefs.placeForWidget(10)?.id)
        assertEquals(work.id, prefs.placeForWidget(11)?.id)
        assertEquals(listOf(11), prefs.widgetsFor(work.id))

        prefs.deletePlace(work.id)
        assertEquals(1, prefs.placeForWidget(11)?.id)
        assertFalse(prefs.isBound(11))

        prefs.deletePlace(1)
        assertEquals(1, prefs.places.size)
    }

    @Test fun idsAreNeverReused() {
        val prefs = Prefs(ctx)
        val a = prefs.addPlace()
        prefs.deletePlace(a.id)
        assertTrue(prefs.addPlace().id > a.id)
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(Place(1, "Дім", "odeska-oblast", "2.1"), Place(3, "", null, null, false, false))
        assertEquals(list, Place.listFromJson(Place.listToJson(list)))
        assertEquals(emptyList<Place>(), Place.listFromJson("not json"))
    }

    @Test fun samePlaceScheduleIsFollowedOnce() {
        val places = listOf(
            Place(1, "Home", "odeska-oblast", "2.1"),
            Place(2, "Mum", "odeska-oblast", "2.1", notifyBefore = false),
            Place(3, "Work", "kyiv", "3.1"),
            Place(4, "Empty", null, null),
        )
        val all = Schedules.followed(places)
        assertEquals(listOf("odeska-oblast_2.1", "kyiv_3.1"), all.map { it.key })
        assertEquals(listOf(1, 2), all.first().places.map { it.id })
        val reminders = Schedules.followed(places) { it.notifyBefore }
        assertEquals(listOf(1), reminders.first().places.map { it.id })
    }
}
