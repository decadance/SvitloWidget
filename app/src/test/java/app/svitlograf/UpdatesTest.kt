package app.svitlograf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdatesTest {
    private val json = """
        {"versionCode": 3, "versionName": "1.2.0", "file": "svitlograf-1.2.0.apk", "bytes": 5, "sha256": "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824",
         "notes": {"en": "New", "uk": "Нове"}, "publishedAt": "2026-11-06T12:00:00+00:00"}
    """.trimIndent()

    @Test fun parsesSleepHubStyleManifest() {
        val r = Release.parse(json)
        assertEquals(3, r.versionCode)
        assertEquals("Нове", r.notes("uk"))
        assertEquals("New", r.notes("de"))
        assertEquals(r, Release.parse(r.toJson()))
    }

    @Test fun comparesVersionCodes() {
        val r = Release.parse(json)
        assertTrue(Updates.isNewer(r, current = 2))
        assertFalse(Updates.isNewer(r, current = 3))
    }

    @Test fun rejectsPathsInFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            Release.parse(json.replace("svitlograf-1.2.0.apk", "../evil.apk"))
        }
    }

    @Test fun verifiesSizeAndChecksum() {
        val r = Release.parse(json)
        val f = File.createTempFile("upd", ".apk").apply { writeText("hello") }
        Updates.verify(f, r)
        val bad = File.createTempFile("upd", ".apk").apply { writeText("hellO") }
        assertThrows(IllegalStateException::class.java) { Updates.verify(bad, r) }
        assertFalse(bad.exists())
    }
}
