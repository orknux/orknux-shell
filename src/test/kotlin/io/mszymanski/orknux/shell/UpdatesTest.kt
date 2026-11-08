package io.mszymanski.orknux.shell

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class UpdatesTest {
    @TempDir lateinit var dir: Path

    private val ours: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val stranger: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val newBytes = "the new orknux".repeat(1000).toByteArray()
    private val oldBytes = "the old orknux".toByteArray()

    /** What the stand-in orknux.ai answers: [latest] for the check, [served] for the download. */
    private var latest: (String) -> Pair<Int, String> = { 404 to """{"error":"no-release","message":"none"}""" }
    private var served: ByteArray = newBytes
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/clients/shell/latest") { x ->
            val (status, body) = latest(x.requestURI.rawQuery ?: "")
            val bytes = body.toByteArray()
            x.sendResponseHeaders(status, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        createContext("/download/") { x ->
            x.sendResponseHeaders(200, served.size.toLong())
            x.responseBody.use { it.write(served) }
        }
        start()
    }
    private val site = "http://127.0.0.1:${server.address.port}"

    @AfterEach fun stop() = server.stop(0)

    private fun sign(bytes: ByteArray, key: KeyPair = ours): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(key.private); update(bytes); sign() })

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun answer(version: String = "1.0.4", bytes: ByteArray = newBytes, signature: String = sign(bytes), sha: String = sha256(bytes), size: Long = bytes.size.toLong(), withdrawn: String? = null) =
        """{"client":"SHELL","version":"$version","publishedAt":"2026-11-09T10:00:00Z","changelog":"- better",
           "upToDate":false,"minimumServerVersion":null,
           "file":{"platform":"linux-x64","fileName":"orknux-shell-linux-x64","downloadUrl":"$site/download/shell/$version/orknux-shell-linux-x64",
                   "sha256":"$sha","size":$size,"signature":"$signature"},
           "withdrawnCurrent":${withdrawn?.let { """{"reason":"$it"}""" } ?: "null"}}"""

    private fun installedFile(): Path = dir.resolve("orknux").also { Files.write(it, oldBytes) }

    private fun updater(target: Path = installedFile(), current: String = "1.0.3", windows: Boolean = false) =
        Updater(site, Installed("linux-x64", target), current, listOf(ours.public), windows)

    @Test
    fun `a signed newer release replaces the program, and the check said which platform and version asked`() {
        var asked = ""
        latest = { q -> asked = q; 200 to answer() }
        val target = installedFile()
        val u = updater(target)
        val offer = u.latest()
        assertEquals("platform=linux-x64&current=1.0.3", asked)
        assertTrue(u.newer(offer))
        u.install(offer)
        assertArrayEquals(newBytes, Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve(".orknux.download")), "nothing left beside it")
    }

    @Test
    fun `a signature by any other key, a wrong digest, a wrong size or a short download change nothing`() {
        val cases = listOf(
            answer(signature = sign(newBytes, stranger)) to "not signed by Orknux's release key",
            answer(signature = Base64.getEncoder().encodeToString(ByteArray(10))) to "not signed",
            answer(sha = sha256("other".toByteArray())) to "sha256",
            answer(size = newBytes.size + 5L) to "bytes, not the",
        )
        for ((body, why) in cases) {
            latest = { 200 to body }
            val target = installedFile()
            val u = updater(target)
            val refused = assertThrows(OrknuxException::class.java) { u.install(u.latest()) }
            assertTrue(refused.message!!.contains(why), refused.message)
            assertTrue(refused.message!!.contains("Nothing was changed") || refused.message!!.contains("not installed"), refused.message)
            assertArrayEquals(oldBytes, Files.readAllBytes(target), why)
        }
        // A file swapped on the site after it was signed: the digest the site states is of the new bytes, the signature is not.
        served = "tampered".toByteArray()
        latest = { 200 to answer(bytes = served, signature = sign(newBytes)) }
        val target = installedFile()
        val u = updater(target)
        assertThrows(OrknuxException::class.java) { u.install(u.latest()) }
        assertArrayEquals(oldBytes, Files.readAllBytes(target))
    }

    @Test
    fun `never a step back, whatever the site says`() {
        latest = { 200 to answer(version = "1.0.2") }
        val target = installedFile()
        val u = updater(target)
        val offer = u.latest()
        assertFalse(u.newer(offer))
        assertThrows(OrknuxException::class.java) { u.install(offer) }
        assertArrayEquals(oldBytes, Files.readAllBytes(target))
    }

    @Test
    fun `on Windows the running program is moved aside, and tidied away at the next start`() {
        latest = { 200 to answer() }
        val target = installedFile()
        val u = updater(target, windows = true)
        u.install(u.latest())
        assertArrayEquals(newBytes, Files.readAllBytes(target))
        assertArrayEquals(oldBytes, Files.readAllBytes(Updater.aside(target)))
        Updater.tidy(Installed("windows-x64", target))
        assertFalse(Files.exists(Updater.aside(target)))
    }

    @Test
    fun `the site's refusals are said in words`() {
        latest = { 404 to """{"error":"no-release","message":"Nothing of SHELL has been published yet."}""" }
        assertTrue(assertThrows(OrknuxException::class.java) { updater().latest() }.message!!.contains("Nothing of Orknux Shell has been published"))
        latest = { 404 to """{"error":"no-platform","message":"x"}""" }
        assertTrue(assertThrows(OrknuxException::class.java) { updater().latest() }.message!!.contains("no file for linux-x64"))
        val notInstalled = Updater(site, null, "1.0.3", listOf(ours.public))
        assertEquals(Updater.NOT_INSTALLED, assertThrows(OrknuxException::class.java) { notInstalled.latest() }.message)
    }

    @Test
    fun `the daily check asks once a day, and says what it found in between`() {
        val store = ConfigStore(dir.resolve("config.json"))
        var asks = 0
        latest = { asks++; 200 to answer(version = "99.0.0") }
        val u = updater()
        val day = Updater.CHECK_EVERY.toMillis()
        assertEquals("99.0.0", DailyCheck.run(store, u, now = 1_000, env = emptyMap())?.newer)
        assertEquals("99.0.0", DailyCheck.run(store, u, now = 1_000 + day / 2, env = emptyMap())?.newer, "from what was found")
        assertEquals(1, asks)
        DailyCheck.run(store, u, now = 1_000 + day, env = emptyMap())
        assertEquals(2, asks)
        assertNull(DailyCheck.run(store, u, now = 1_000 + 3 * day, env = mapOf("ORKNUX_SHELL_UPDATES" to "off")))
        store.update { it.copy(updates = false) }
        assertNull(DailyCheck.run(store, u, now = 1_000 + 4 * day, env = emptyMap()))
        assertEquals(2, asks, "off is off")
    }

    @Test
    fun `a withdrawn running version is news even with nothing newer`() {
        val store = ConfigStore(dir.resolve("config.json"))
        latest = { 200 to answer(version = Version.VERSION, withdrawn = "it ate config files") }
        val news = DailyCheck.run(store, updater(current = Version.VERSION), env = emptyMap())
        assertNotNull(news)
        assertNull(news!!.newer)
        assertEquals("it ate config files", news.withdrawn)
    }

    @Test
    fun `versions compare as releases are numbered`() {
        assertTrue(Versions.compare("1.0.10", "1.0.9") > 0)
        assertTrue(Versions.compare("1.1.0", "1.0.99") > 0)
        assertTrue(Versions.compare("1.0.4", "1.0.4-beta.1") > 0)
        assertTrue(Versions.compare("1.0.4-beta.2", "1.0.4-beta.1") > 0)
        assertTrue(Versions.compare("1.0.4-beta.10", "1.0.4-beta.9") > 0)
        assertEquals(0, Versions.compare("v1.0.3", "1.0.3"))
        assertTrue(Versions.compare("1.0.3", "0.0.0-dev") > 0)
    }

    @Test
    fun `the key built in is an Ed25519 key, and verifies what its private half signed`() {
        val keys = Updater.releaseKeys()
        assertEquals(1, keys.size)
        assertEquals("EdDSA", keys[0].algorithm)
        assertFalse(Updater.verified(newBytes, sign(newBytes), keys), "a key made in this test is not the release key")
        assertTrue(Updater.verified(newBytes, sign(newBytes), listOf(ours.public)))
    }

    @Test
    fun `release notes are drawn as Markdown with room between sections, and cut at a section's end`() {
        val notes = "### Added\n\n- One thing.\n- Another.\n\n### Fixed\n\n- A bug.\n"
        val drawn = Interactive.releaseNotes(Theme(depth = ColourDepth.NONE), notes, 80).map { it.trimEnd() }
        assertEquals(listOf("  Added", "", "    • One thing.", "    • Another.", "", "  Fixed", "", "    • A bug."), drawn)
        val cut = Interactive.releaseNotes(Theme(depth = ColourDepth.NONE), notes, 80, most = 6)
        assertEquals("  Added", cut.first())
        assertFalse(cut.any { "A bug" in it }, cut.toString())
        assertTrue(cut.last().contains("CHANGELOG.md"), cut.toString())
    }
}
