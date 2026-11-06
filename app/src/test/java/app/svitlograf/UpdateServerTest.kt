package app.svitlograf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/** The app against a local copy of the Mac mini's update folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateServerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val apk = ByteArray(300_000) { (it * 31).toByte() }
    private var latest = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/svitlograf/") { ex ->
            val path = ex.requestURI.path.removePrefix("/svitlograf/")
            val body = when (path) {
                "latest.json" -> latest.toByteArray()
                "svitlograf-9.0.0.apk" -> apk
                else -> null
            }
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    private fun manifest(code: Int, sha: String = sha(apk)) =
        """{"versionCode":$code,"versionName":"9.0.0","file":"svitlograf-9.0.0.apk","bytes":${apk.size},"sha256":"$sha","notes":{"en":"x"}}"""

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @After fun stop() { server.stop(0); Updates.baseOverride = null }

    @Test fun findsDownloadsAndVerifiesNewerVersion() {
        Updates.baseOverride = "http://127.0.0.1:${server.address.port}/svitlograf"
        latest = manifest(BuildConfig.VERSION_CODE + 1)
        val r = Updates.check(ctx)
        assertNotNull(r)
        assertEquals(r, Updates.available(ctx))
        var last = 0f
        val f = Updates.download(ctx, r!!) { last = it }
        assertEquals(1f, last)
        assertArrayEquals(apk, f.readBytes())
        assertEquals(File(ctx.cacheDir, "updates"), f.parentFile)
    }

    @Test fun ignoresSameVersionAndForgetsStaleOffer() {
        Updates.baseOverride = "http://127.0.0.1:${server.address.port}/svitlograf"
        latest = manifest(BuildConfig.VERSION_CODE + 1)
        assertNotNull(Updates.check(ctx))
        latest = manifest(BuildConfig.VERSION_CODE)
        assertNull(Updates.check(ctx))
        assertNull(Updates.available(ctx))
    }

    @Test fun rejectsTamperedApk() {
        Updates.baseOverride = "http://127.0.0.1:${server.address.port}/svitlograf"
        latest = manifest(BuildConfig.VERSION_CODE + 1, sha = "0".repeat(64))
        val r = Updates.check(ctx)!!
        val err = runCatching { Updates.download(ctx, r) {} }.exceptionOrNull()
        assertEquals("checksum mismatch", err?.message)
        assertEquals(0, File(ctx.cacheDir, "updates").listFiles()!!.size)
    }

    @Test fun serverDownKeepsQuiet() {
        Updates.baseOverride = "http://127.0.0.1:1/svitlograf"
        assertNull(Updates.check(ctx))
    }
}
