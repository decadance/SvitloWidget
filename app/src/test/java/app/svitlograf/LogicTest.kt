package app.svitlograf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LogicTest {
    private val feed = Parser.parse(javaClass.getResource("/sample.json")!!.readText())
    private val odesa = feed.region("odeska-oblast")!!
    private val day = LocalDate.of(2026, 11, 4)

    @Test fun parsesAllRegions() {
        assertEquals(listOf("odeska-oblast", "lvivska-oblast", "kyiv", "dnipro-dnem", "chernivetska-oblast"), feed.regions.map { it.id })
        assertEquals("Одеська область", odesa.nameUk)
        assertEquals("Odesa Region", odesa.nameEn)
    }

    @Test fun group21MatchesPublishedSchedule() {
        assertEquals("03:30–10:30, 14:00–18:00", Logic.formatDay(odesa.days("2.1").first { it.date == day }))
    }

    @Test fun groupsMatchNews() {
        // Groups where grad.ua and odessa.online agree, 4 Nov 2026
        mapOf("1.1" to "03:30–07:30, 14:00–21:30", "4.1" to "08:00–14:30, 17:30–21:30").forEach { (g, s) ->
            assertEquals(g, s, Logic.formatDay(odesa.days(g).first { it.date == day }))
        }
    }

    @Test fun naturalGroupOrder() {
        assertEquals(listOf("1.1", "2.1", "9.1", "10.1", "60.1"), listOf("10.1", "2.1", "60.1", "1.1", "9.1").sortedWith(GroupOrder))
        assertEquals(listOf("1", "2", "10", "12"), listOf("12", "10", "2", "1").sortedWith(GroupOrder))
        assertEquals("1.1", feed.region("kyiv")!!.sortedGroups.first())
    }

    @Test fun snapshotBeforeAfternoonOutage() {
        val s = Snapshot.build(odesa, "2.1", LocalDateTime.of(2026, 11, 4, 13, 58))
        assertEquals(Power.ON, s.power)
        assertEquals(LocalDateTime.of(2026, 11, 4, 14, 0), s.nextChange)
    }

    @Test fun snapshotDuringOutage() {
        val s = Snapshot.build(odesa, "2.1", LocalDateTime.of(2026, 11, 4, 15, 10))
        assertEquals(Power.OFF, s.power)
        assertEquals(LocalDateTime.of(2026, 11, 4, 18, 0), s.nextChange)
    }

    @Test fun eveningWithoutTomorrow() {
        val s = Snapshot.build(odesa, "2.1", LocalDateTime.of(2026, 11, 4, 19, 0))
        assertEquals(Power.ON, s.power)
        assertNull(s.nextChange)
    }

    @Test fun unknownCases() {
        assertEquals(Power.UNKNOWN, Snapshot.build(odesa, "2.1", LocalDateTime.of(2026, 12, 1, 12, 0)).power)
        assertEquals(Power.UNKNOWN, Snapshot.build(odesa, null, LocalDateTime.of(2026, 11, 4, 12, 0)).power)
        assertEquals(Power.UNKNOWN, Snapshot.build(feed.region("dnipro-dnem"), "1.1", LocalDateTime.of(2026, 11, 4, 12, 0)).power)
    }

    @Test fun intervalsMergeAcrossMidnight() {
        val a = BooleanArray(SLOTS).also { for (i in 42 until 48) it[i] = true }
        val b = BooleanArray(SLOTS).also { for (i in 0 until 8) it[i] = true }
        val iv = Logic.intervals(listOf(DaySlots(day, a), DaySlots(day.plusDays(1), b)))
        assertEquals(1, iv.size)
        assertEquals(LocalDateTime.of(2026, 11, 4, 21, 0), iv[0].start)
        assertEquals(LocalDateTime.of(2026, 11, 5, 4, 0), iv[0].end)
        assertEquals("21:00–24:00", Logic.formatDay(DaySlots(day, a)))
    }

    @Test fun nextChangeCrossesIntoTomorrow() {
        val a = BooleanArray(SLOTS).also { for (i in 42 until 48) it[i] = true }
        val b = BooleanArray(SLOTS).also { for (i in 0 until 8) it[i] = true }
        val rd = RegionData("x", "x", "x", false, mapOf("5.1" to listOf(DaySlots(day, a), DaySlots(day.plusDays(1), b))))
        val s = Snapshot.build(rd, "5.1", LocalDateTime.of(2026, 11, 4, 22, 0))
        assertEquals(Power.OFF, s.power)
        assertEquals(LocalDateTime.of(2026, 11, 5, 4, 0), s.nextChange)
    }

    @Test fun noOutagesFormatsAsNull() {
        assertNull(Logic.formatDay(DaySlots(day, BooleanArray(SLOTS))))
    }

    @Test fun diffDetectsNewAndChangedDays() {
        val seen = odesa.days("2.1").associate { it.date to it.signature }
        assertTrue(Diff.changes(seen, odesa, "2.1", day).isEmpty())
        val rd = RegionData("x", "x", "x", false, mapOf("2.1" to listOf(
            DaySlots(day, BooleanArray(SLOTS).also { it[30] = true }),
            DaySlots(day.plusDays(1), BooleanArray(SLOTS).also { it[10] = true }),
        )))
        assertEquals(listOf(Change.DayChanged(day), Change.NewDay(day.plusDays(1))), Diff.changes(seen, rd, "2.1", day))
    }

    @Test fun diffIgnoresPastDays() {
        val rd = RegionData("x", "x", "x", false, mapOf("2.1" to listOf(DaySlots(day.minusDays(1), BooleanArray(SLOTS)))))
        assertTrue(Diff.changes(emptyMap(), rd, "2.1", day).isEmpty())
    }
}
