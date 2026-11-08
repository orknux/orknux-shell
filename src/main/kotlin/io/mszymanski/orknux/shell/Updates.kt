package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

/** The newest release orknux.ai offers for this platform. */
data class Offer(
    val version: String,
    val publishedAt: String?,
    val changelog: String?,
    val file: OfferedFile,
    /** Why the running version was withdrawn, where it was. */
    val withdrawnCurrent: String?,
)

data class OfferedFile(val platform: String, val fileName: String, val downloadUrl: String, val sha256: String, val size: Long, val signature: String)

/** What is running and where it lives: the file an update replaces. */
data class Installed(val platform: String, val path: Path)

/**
 * Self-update, from orknux.ai (docs/orknux-ai/client-releases.graphql, section 4).
 *
 * The site says which release is newest, but it is not what is trusted: it
 * hands over a signature it was given and cannot make one. A download is put in
 * place only when its size and sha256 are the ones offered, its Ed25519
 * signature verifies against a key built into this program
 * (`release-keys.pem`, the key the release CI signs with), and it is newer than
 * what runs. Anything else leaves the installed file exactly as it was.
 */
class Updater(
    private val site: String = siteUrl(),
    private val installed: Installed? = installed(),
    private val current: String = Version.VERSION,
    private val keys: List<PublicKey> = releaseKeys(),
    private val windows: Boolean = Platform.isWindows(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Null for a build that is not an installed program - run from classes, or on a platform nothing is published for. */
    val platform: String? get() = installed?.platform

    /** The newest release, or a refusal in words. */
    fun latest(): Offer {
        val platform = installed?.platform ?: throw OrknuxException(NOT_INSTALLED)
        val query = "platform=$platform&current=" + URLEncoder.encode(current, StandardCharsets.UTF_8)
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create("$site/api/clients/shell/latest?$query"))
                    .header("Accept", "application/json")
                    .header("User-Agent", "OrknuxShell/$current")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: java.io.IOException) {
            throw OrknuxException("Could not reach $site to look for an update: ${e.message ?: e.javaClass.simpleName}.")
        }
        val body = runCatching { Json.parse(response.body()) }.getOrNull()
        if (response.statusCode() == 404 && body?.str("error") == "no-release") throw OrknuxException("Nothing of Orknux Shell has been published on $site yet.")
        if (response.statusCode() == 404 && body?.str("error") == "no-platform") throw OrknuxException("The newest release has no file for $platform.")
        if (response.statusCode() != 200 || body == null) {
            throw OrknuxException("$site answered ${response.statusCode()} when asked for an update" + (body?.str("message")?.let { ": $it" } ?: "."))
        }
        return offerOf(body)
    }

    /** Whether [offer] is a version to move to: strictly newer, never a step back. */
    fun newer(offer: Offer): Boolean = Versions.compare(offer.version, current) > 0

    /**
     * Downloads [offer] beside the installed file, checks it, and puts it in
     * place. [progress] hears the bytes as they come. The path it now lives at.
     */
    fun install(offer: Offer, progress: (Long, Long) -> Unit = { _, _ -> }): Path {
        val target = installed?.path ?: throw OrknuxException(NOT_INSTALLED)
        if (!newer(offer)) throw OrknuxException("Orknux Shell ${offer.version} is not newer than $current; nothing was changed.")
        val file = offer.file
        if (file.platform != installed.platform) throw OrknuxException("The update offered is for ${file.platform}, not ${installed.platform}; nothing was changed.")
        val dir = target.parent ?: throw OrknuxException("Cannot tell which directory $target is in.")
        if (!Files.isWritable(dir)) {
            throw OrknuxException("$dir cannot be written to by you, so the update cannot be put there. Run the installer again, or update it the way it was installed.")
        }
        val download = dir.resolve(".${target.fileName}.download")
        try {
            fetch(file, download, progress)
            check(file, download)
            putInPlace(download, target)
        } finally {
            runCatching { Files.deleteIfExists(download) }
        }
        return target
    }

    private fun fetch(file: OfferedFile, download: Path, progress: (Long, Long) -> Unit) {
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create(file.downloadUrl)).header("User-Agent", "OrknuxShell/$current")
                    .timeout(Duration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: java.io.IOException) {
            throw OrknuxException("The download failed: ${e.message ?: e.javaClass.simpleName}. Nothing was changed.")
        }
        response.body().use { input ->
            if (response.statusCode() != 200) throw OrknuxException("The download answered ${response.statusCode()}. Nothing was changed.")
            Files.newOutputStream(download).use { output ->
                val buffer = ByteArray(1 shl 16)
                var seen = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    seen += n
                    if (seen > file.size) throw OrknuxException("The download is longer than the ${file.size} bytes offered. Nothing was changed.")
                    output.write(buffer, 0, n)
                    progress(seen, file.size)
                }
            }
        }
    }

    /** Size, digest and signature, in that order; the first that fails is the reason given. */
    private fun check(file: OfferedFile, download: Path) {
        val size = Files.size(download)
        if (size != file.size) throw OrknuxException("The download is $size bytes, not the ${file.size} offered. Nothing was changed.")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(download).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha256.equals(file.sha256, ignoreCase = true)) throw OrknuxException("The download's sha256 is not the one offered. Nothing was changed.")
        val bytes = Files.readAllBytes(download)
        if (!verified(bytes, file.signature, keys)) {
            throw OrknuxException("The download is not signed by Orknux's release key, so it was not installed. Nothing was changed.")
        }
    }

    /**
     * Linux: one rename over the old file, which the running process keeps open.
     * Windows will not replace a running program but lets it be renamed, so it
     * is moved aside as `.old` - removed at the next start - and put back if
     * the new one cannot be moved in.
     */
    private fun putInPlace(download: Path, target: Path) {
        if (!windows) {
            val permissions = runCatching { Files.getPosixFilePermissions(target) }.getOrNull()
                ?: PosixFilePermissions.fromString("rwxr-xr-x")
            runCatching { Files.setPosixFilePermissions(download, permissions) }
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        }
        val aside = aside(target)
        runCatching { Files.deleteIfExists(aside) }
        Files.move(target, aside, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(download, target)
        } catch (e: Exception) {
            Files.move(aside, target, StandardCopyOption.REPLACE_EXISTING)
            throw OrknuxException("The new version could not be moved into place (${e.message}). Nothing was changed.")
        }
    }

    companion object {
        const val SITE = "https://orknux.ai"
        const val NOT_INSTALLED = "This Orknux Shell is not an installed build (it runs from classes, or on a platform nothing is published for), so it cannot update itself."
        /** How often a running Shell looks again; it also looks at every start. Releases come more often than daily. */
        val CHECK_EVERY: Duration = Duration.ofHours(1)

        /** `ORKNUX_SHELL_UPDATE_URL` points the check at another site, for testing one. */
        fun siteUrl(env: Map<String, String> = System.getenv()): String = env["ORKNUX_SHELL_UPDATE_URL"]?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: SITE

        /** The daily check is off where the environment or the configuration says so: packaged and air-gapped installs. */
        fun checksOff(config: Config, env: Map<String, String> = System.getenv()): Boolean =
            !config.updates || env["ORKNUX_SHELL_UPDATES"]?.trim()?.lowercase() in setOf("off", "false", "0", "no")

        fun aside(target: Path): Path = target.resolveSibling(target.fileName.toString() + ".old")

        /** What a Windows update left behind: the old program, which could not be deleted while it ran. */
        fun tidy(installed: Installed? = installed()) {
            installed?.let { runCatching { Files.deleteIfExists(aside(it.path)) } }
        }

        /** What runs: the native binary, or the jar a JVM was started on. */
        fun installed(
            os: String = System.getProperty("os.name"),
            arch: String = System.getProperty("os.arch"),
            native: Boolean = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime",
        ): Installed? = runCatching {
            if (native) {
                val command = ProcessHandle.current().info().command().orElse(null) ?: return null
                val x64 = arch.lowercase() in setOf("amd64", "x86_64", "x64")
                val platform = when {
                    !x64 -> null
                    Platform.isWindows(os) -> "windows-x64"
                    os.lowercase().startsWith("linux") -> "linux-x64"
                    else -> null
                } ?: return null
                Installed(platform, Path.of(command).toRealPath())
            } else {
                val location = Updater::class.java.protectionDomain?.codeSource?.location ?: return null
                val path = Path.of(location.toURI())
                if (!path.fileName.toString().endsWith(".jar") || !Files.isRegularFile(path)) return null
                Installed("any", path.toRealPath())
            }
        }.getOrNull()

        /** The keys a release may be signed with: more than one only while a rotation is under way. */
        fun releaseKeys(): List<PublicKey> {
            val text = Updater::class.java.getResourceAsStream("/io/mszymanski/orknux/shell/release-keys.pem")
                ?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) } ?: return emptyList()
            return keysFrom(text)
        }

        fun keysFrom(pem: String): List<PublicKey> =
            Regex("-----BEGIN PUBLIC KEY-----([^-]+)-----END PUBLIC KEY-----").findAll(pem).map { match ->
                val der = Base64.getMimeDecoder().decode(match.groupValues[1].trim())
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
            }.toList()

        fun verified(bytes: ByteArray, signature: String, keys: List<PublicKey>): Boolean {
            val raw = runCatching { Base64.getDecoder().decode(signature.trim()) }.getOrNull() ?: return false
            if (raw.size != 64) return false
            return keys.any { key ->
                runCatching {
                    Signature.getInstance("Ed25519").run {
                        initVerify(key)
                        update(bytes)
                        verify(raw)
                    }
                }.getOrDefault(false)
            }
        }

        fun offerOf(body: JsonNode): Offer {
            val file = body.get("file") ?: throw OrknuxException("The update answer names no file.")
            fun need(node: JsonNode, name: String) = node.str(name) ?: throw OrknuxException("The update answer has no $name.")
            return Offer(
                version = need(body, "version"),
                publishedAt = body.str("publishedAt"),
                changelog = body.str("changelog"),
                file = OfferedFile(
                    platform = need(file, "platform"),
                    fileName = need(file, "fileName"),
                    downloadUrl = need(file, "downloadUrl"),
                    sha256 = need(file, "sha256"),
                    size = file.get("size")?.takeIf { it.isNumber }?.asLong() ?: throw OrknuxException("The update answer has no size."),
                    signature = need(file, "signature"),
                ),
                withdrawnCurrent = body.get("withdrawnCurrent")?.takeIf { it.isObject }?.let { it.str("reason") ?: "no reason given" },
            )
        }
    }
}

/** What the daily look found worth a line: a newer version, or that the running one was withdrawn. */
data class UpdateNews(val newer: String?, val withdrawn: String?)

/**
 * The look for an update: orknux.ai at every start and then every hour - a
 * look once a day kept a version released that morning unsaid all day, even
 * across restarts - and in between, what the last look found - so a day that does not ask still
 * says a newer version is out. Never updates anything; a failure says nothing,
 * because a Shell offline is not news.
 */
object DailyCheck {
    fun run(store: ConfigStore, updater: Updater, now: Long = System.currentTimeMillis(), env: Map<String, String> = System.getenv(), force: Boolean = false): UpdateNews? {
        val config = store.load()
        if (Updater.checksOff(config, env) || updater.platform == null) return null
        val due = force || config.updateCheckedAt?.let { now - it >= Updater.CHECK_EVERY.toMillis() || it > now } ?: true
        if (!due) {
            val known = config.updateLatest ?: return null
            return if (Versions.compare(known, Version.VERSION).let { it > 0 }) UpdateNews(known, null) else null
        }
        val offer = runCatching { updater.latest() }.getOrNull()
        store.update { it.copy(updateCheckedAt = now, updateLatest = offer?.version) }
        offer ?: return null
        val newer = offer.version.takeIf { updater.newer(offer) }
        if (newer == null && offer.withdrawnCurrent == null) return null
        return UpdateNews(newer, offer.withdrawnCurrent)
    }
}

/** Versions as releases are numbered: X.Y.Z, and X.Y.Z-pre before it, compared as semantic versioning does. */
object Versions {
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = (coreA.getOrElse(i) { 0 }).compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        val partsA = preA.split('.')
        val partsB = preB.split('.')
        for (i in 0 until minOf(partsA.size, partsB.size)) {
            val x = partsA[i]
            val y = partsB[i]
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return partsA.size.compareTo(partsB.size)
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = v.trim().removePrefix("v").substringBefore('+')
        val core = s.substringBefore('-')
        val pre = if ('-' in s) s.substringAfter('-') else null
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
