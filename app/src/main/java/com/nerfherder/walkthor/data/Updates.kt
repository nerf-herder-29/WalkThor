package com.nerfherder.walkthor.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Updates for your emulators (the apps WalkThor counts as emulators) and for WalkThor itself:
 * each one's release page (GitHub, or a Forgejo / Gitea site) is checked for its newest release,
 * which is compared with the version installed, and can be downloaded and handed to Android's
 * installer. Nothing breaks without the internet: the last results stay, marked as such.
 *
 * Only the release pages are contacted, and only when you look at Updates (at most every few
 * hours) or tap Check now. Nothing about you or your apps is sent.
 */
object Updates {
    enum class State { NO_PAGE, CHECKING, UP_TO_DATE, UPDATE, NO_APK, PAGE_NOT_FOUND, FAILED, DOWNLOADING, WRONG_SIGNER, INSTALLING }

    data class Entry(
        val pkg: String,
        val name: String,
        val installed: String?,
        val page: String?,
        val latest: String? = null,
        val notes: String? = null,
        val published: Long = 0L,
        val apkUrl: String? = null,
        val apkSize: Long = 0L,
        val state: State,
        val problem: String? = null,
    )

    /** WalkThor's own releases. */
    const val WALKTHOR_PAGE = "https://github.com/nerf-herder-29/WalkThor"

    /** Emulators WalkThor knows the release pages of, by a word in their name or package. */
    private val KNOWN = listOf(
        "azahar" to "https://github.com/azahar-emu/azahar",
        "borked3ds" to "https://github.com/Borked3DS/Borked3DS",
        "melonds" to "https://github.com/rafaelvcaetano/melonDS-android",
        "flycast" to "https://github.com/flyinghead/flycast",
        "winlator" to "https://github.com/brunodev85/winlator",
        "gamenative" to "https://github.com/utkarshdalal/GameNative",
        "vita3k" to "https://github.com/Vita3K/Vita3K-Android",
        "ppsspp" to "https://github.com/hrydgard/ppsspp",
        "cemu" to "https://github.com/SSimco/Cemu",
    )

    val entries = MutableStateFlow<List<Entry>>(emptyList())
    /** When the release pages were last reached (0 = never). */
    val checkedAt = MutableStateFlow(0L)
    /** The last check couldn't reach the internet. */
    val offline = MutableStateFlow(false)
    val checking = MutableStateFlow(false)
    /** Download progress, 0..1, by package. */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor updates").apply { isDaemon = true } }
    private val downloads = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor downloads").apply { isDaemon = true } }
    private const val AUTO_EVERY_MS = 6 * 60 * 60 * 1000L

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** How many updates are waiting (for the badge). */
    fun waiting(list: List<Entry>) = list.count { it.state == State.UPDATE }

    // ---- what's listed ----

    /** WalkThor, then your emulators, from what's installed and what was found last time. */
    fun load(c: Context) {
        val app = c.applicationContext
        worker.execute {
            runCatching {
                val cache = readCache(app)
                checkedAt.value = prefs(app).getLong("checkedAt", 0L)
                offline.value = prefs(app).getBoolean("offline", false)
                entries.value = listed(app).map { (pkg, name) -> fromCache(app, pkg, name, cache.optJSONObject(pkg)) }
            }
        }
    }

    /** Looks again if it's been a while (and only then): for opening Updates. */
    fun loadAndMaybeCheck(c: Context) {
        load(c)
        val since = System.currentTimeMillis() - prefs(c).getLong("checkedAt", 0L)
        if (since > AUTO_EVERY_MS || offline.value) check(c)
    }

    private fun listed(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo?.packageName }.distinct()
        }.getOrDefault(emptyList())
        val emus = launchable.filter { it != c.packageName && com.nerfherder.walkthor.EmulatorWatch.isEmulator(c, it) }
            .map { it to label(c, it) }
            .sortedBy { it.second.lowercase() }
        return listOf(c.packageName to "WalkThor") + emus
    }

    private fun label(c: Context, pkg: String) =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun installedVersion(c: Context, pkg: String): String? =
        runCatching { c.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    private fun installedAt(c: Context, pkg: String): Long =
        runCatching { c.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)

    /** The release page for [pkg]: yours if you've set one, else one WalkThor knows. */
    fun pageFor(c: Context, pkg: String): String? {
        if (pkg == c.packageName) return WALKTHOR_PAGE
        prefs(c).getString("page.$pkg", null)?.let { return it.ifBlank { null } }
        val hay = (pkg + " " + label(c, pkg)).lowercase().replace(" ", "")
        return KNOWN.firstOrNull { (word, _) -> word in hay }?.second
    }

    /** Sets (or, with blank, clears) the release page for [pkg], and checks it. */
    fun setPage(c: Context, pkg: String, url: String) {
        prefs(c).edit().putString("page.$pkg", normalisePage(url) ?: "").apply()
        check(c, only = pkg)
    }

    /** github.com/owner/repo (or any Forgejo / Gitea site's owner/repo), from a pasted link. */
    fun normalisePage(url: String): String? {
        val u = runCatching { Uri.parse(url.trim().let { if ("://" in it) it else "https://$it" }) }.getOrNull() ?: return null
        val host = u.host ?: return null
        val parts = u.pathSegments.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return "https://$host/${parts[0]}/${parts[1].removeSuffix(".git")}"
    }

    // ---- checking ----

    /** Checks every release page (or just [only]'s). Never throws; without the internet, keeps what it had. */
    fun check(c: Context, only: String? = null) {
        val app = c.applicationContext
        if (checking.value && only == null) return
        worker.execute {
            checking.value = true
            try {
                val cache = readCache(app)
                val list = listed(app)
                if (!online(app)) {
                    offline.value = true
                    prefs(app).edit().putBoolean("offline", true).apply()
                    entries.value = list.map { (pkg, name) -> fromCache(app, pkg, name, cache.optJSONObject(pkg)) }
                    return@execute
                }
                var reached = false
                var lostConnection = false
                entries.value = list.map { (pkg, name) ->
                    val cached = cache.optJSONObject(pkg)
                    if ((only != null && pkg != only) || lostConnection) return@map fromCache(app, pkg, name, cached)
                    val page = pageFor(app, pkg) ?: return@map fromCache(app, pkg, name, null)
                    when (val r = fetchLatest(page)) {
                        is Fetch.Found -> {
                            reached = true
                            val o = JSONObject()
                                .put("page", page).put("latest", r.tag).put("notes", r.notes.take(6000))
                                .put("published", r.published).put("apkUrl", r.apkUrl ?: "").put("apkSize", r.apkSize)
                            cache.put(pkg, o)
                            fromCache(app, pkg, name, o)
                        }
                        Fetch.NotFound -> {
                            reached = true
                            cache.put(pkg, JSONObject().put("page", page).put("notFound", true))
                            fromCache(app, pkg, name, cache.optJSONObject(pkg))
                        }
                        Fetch.Offline -> { lostConnection = true; fromCache(app, pkg, name, cached) }
                        is Fetch.Failed -> fromCache(app, pkg, name, cached).let { e ->
                            if (e.latest == null) e.copy(state = State.FAILED, problem = r.why) else e
                        }
                    }
                }
                writeCache(app, cache)
                offline.value = lostConnection && !reached
                val now = System.currentTimeMillis()
                prefs(app).edit().putBoolean("offline", offline.value).apply {
                    if (reached) putLong("checkedAt", now)
                }.apply()
                if (reached) checkedAt.value = now
            } catch (_: Throwable) {
                // Whatever went wrong, the list stays as it was.
            } finally {
                checking.value = false
            }
        }
    }

    private fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private sealed class Fetch {
        class Found(val tag: String, val notes: String, val published: Long, val apkUrl: String?, val apkSize: Long) : Fetch()
        object NotFound : Fetch()
        object Offline : Fetch()
        class Failed(val why: String) : Fetch()
    }

    /** The newest (non-pre-release) release on [page], from its site's API. */
    private fun fetchLatest(page: String): Fetch {
        val u = Uri.parse(page)
        val parts = u.pathSegments
        if (parts.size < 2) return Fetch.NotFound
        val (owner, repo) = parts[0] to parts[1]
        val api = if (u.host.equals("github.com", true)) "https://api.github.com/repos/$owner/$repo/releases/latest"
        else "https://${u.host}/api/v1/repos/$owner/$repo/releases/latest"
        return try {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WalkThor")
            }
            val code = conn.responseCode
            if (code == 404) return Fetch.NotFound
            if (code == 403 || code == 429) return Fetch.Failed("the release site asked to wait a while (too many checks)")
            if (code !in 200..299) return Fetch.Failed("the release site answered $code")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name").ifBlank { o.optString("name") }
            val assets = o.optJSONArray("assets") ?: JSONArray()
            var best: JSONObject? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.optString("name").lowercase()
                if (!n.endsWith(".apk")) continue
                var s = 0
                if ("arm64" in n || "aarch64" in n || "v8a" in n) s += 3
                if ("universal" in n) s += 2
                if ("release" in n) s += 1
                if ("x86" in n || "x64" in n || "armeabi-v7a" in n || "armv7" in n) s -= 5
                if ("debug" in n) s -= 2
                if (s > bestScore) { bestScore = s; best = a }
            }
            val published = runCatching { java.time.Instant.parse(o.optString("published_at")).toEpochMilli() }.getOrDefault(0L)
            Fetch.Found(tag, o.optString("body"), published, best?.optString("browser_download_url"), best?.optLong("size") ?: 0L)
        } catch (e: java.net.UnknownHostException) {
            Fetch.Offline
        } catch (e: java.net.ConnectException) {
            Fetch.Offline
        } catch (e: java.net.SocketTimeoutException) {
            Fetch.Offline
        } catch (e: Throwable) {
            Fetch.Failed(e.javaClass.simpleName)
        }
    }

    private fun fromCache(c: Context, pkg: String, name: String, o: JSONObject?): Entry {
        val installed = installedVersion(c, pkg)
        val page = pageFor(c, pkg)
        if (page == null) return Entry(pkg, name, installed, null, state = State.NO_PAGE)
        if (o == null || o.optString("page") != page) return Entry(pkg, name, installed, page, state = State.CHECKING)
        if (o.optBoolean("notFound")) return Entry(pkg, name, installed, page, state = State.PAGE_NOT_FOUND)
        val latest = o.optString("latest").ifBlank { null }
        val apkUrl = o.optString("apkUrl").ifBlank { null }
        val published = o.optLong("published")
        val newer = latest != null && isNewer(latest, installed, published, installedAt(c, pkg))
        val signer = prefs(c).getString("wrongSigner.$pkg", null)
        val state = when {
            !newer -> State.UP_TO_DATE
            signer == latest -> State.WRONG_SIGNER
            apkUrl == null -> State.NO_APK
            else -> State.UPDATE
        }
        return Entry(pkg, name, installed, page, latest, o.optString("notes"), published, apkUrl, o.optLong("apkSize"), state)
    }

    /**
     * Whether release [tag] is newer than [installed]: by their numbers when both have them; when
     * a release isn't numbered like the app (a date, a build name), by whether it came out after
     * this version was installed.
     */
    fun isNewer(tag: String, installed: String?, published: Long, installedAt: Long): Boolean {
        val a = numbers(tag)
        val b = numbers(installed ?: "")
        if (a.isNotEmpty() && b.isNotEmpty() && a.size >= 2 && b.size >= 2) {
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
        if (installed != null && tag.trimStart('v', 'V') == installed.trimStart('v', 'V')) return false
        return published > 0 && installedAt > 0 && published > installedAt + 60 * 60 * 1000L
    }

    private fun numbers(s: String): List<Long> =
        Regex("\\d+").findAll(s.substringBefore('+').substringBefore(" (")).map { it.value.take(12).toLong() }.toList()

    private fun readCache(c: Context) = runCatching { JSONObject(prefs(c).getString("cache", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun writeCache(c: Context, o: JSONObject) = prefs(c).edit().putString("cache", o.toString()).apply()

    // ---- updating ----

    /** Downloads [e]'s newest APK, checks it's the same app from the same developer, and installs it. */
    fun update(c: Context, e: Entry) {
        val app = c.applicationContext
        val url = e.apkUrl ?: return
        if (progress.value.containsKey(e.pkg)) return
        // Android asks once whether WalkThor may install apps.
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            com.nerfherder.walkthor.ScreenMessage.show("Allow WalkThor to install apps, then tap Update again", 3_500)
            return
        }
        if (!online(app)) { com.nerfherder.walkthor.ScreenMessage.show("You're offline: updates need the internet"); return }
        setProgress(e.pkg, 0f)
        downloads.execute {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "${e.pkg}.apk")
            try {
                download(url, file) { setProgress(e.pkg, it) }
                // The same app, signed by the same developer: otherwise Android would refuse it.
                val pm = app.packageManager
                val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                val archive = pm.getPackageArchiveInfo(file.path, flags)
                if (archive == null) { fail(app, e, "The download wasn't a working app"); return@execute }
                if (archive.packageName != e.pkg) { fail(app, e, "That release is a different app (${archive.packageName})"); return@execute }
                val installedInfo = runCatching { pm.getPackageInfo(e.pkg, flags) }.getOrNull()
                if (installedInfo != null && !sameSigner(installedInfo, archive)) {
                    prefs(app).edit().putString("wrongSigner.${e.pkg}", e.latest).apply()
                    clearProgress(e.pkg)
                    file.delete()
                    load(app)
                    com.nerfherder.walkthor.ScreenMessage.show("${e.name} ${e.latest} is signed by a different developer, so Android won't update over yours", 4_000)
                    return@execute
                }
                install(app, e, file)
            } catch (t: Throwable) {
                fail(app, e, if (t is java.net.UnknownHostException || t is java.net.SocketTimeoutException) "The download stopped: check your connection" else "Couldn't download the update")
                file.delete()
            }
        }
    }

    private fun sameSigner(a: android.content.pm.PackageInfo, b: android.content.pm.PackageInfo): Boolean {
        fun sigs(p: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toSet()
        } else @Suppress("DEPRECATION") p.signatures?.toSet()
        val x = sigs(a) ?: return true
        val y = sigs(b) ?: return true
        return x.intersect(y).isNotEmpty()
    }

    private fun download(url: String, to: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "WalkThor")
            instanceFollowRedirects = true
        }
        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        var done = 0L
        var lastShown = -1
        conn.inputStream.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastShown) { lastShown = pct; onProgress(done.toFloat() / total) }
                    }
                }
            }
        }
    }

    private fun install(c: Context, e: Entry, file: File) {
        setProgress(e.pkg, 1f)
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(e.pkg) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("update.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(c, InstallResult::class.java).putExtra("pkg", e.pkg).putExtra("name", e.name)
            val pi = PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(pi.intentSender)
        }
    }

    private fun fail(c: Context, e: Entry, why: String) {
        clearProgress(e.pkg)
        com.nerfherder.walkthor.ScreenMessage.show(why, 3_500)
    }

    private fun setProgress(pkg: String, f: Float) { progress.value = progress.value + (pkg to f) }
    internal fun clearProgress(pkg: String) { progress.value = progress.value - pkg }

    /** Android's answer to an install: asks you to confirm, or says how it went. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra("pkg") ?: return
            val name = intent.getStringExtra("name") ?: pkg
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    clearProgress(pkg)
                    File(File(context.cacheDir, "updates"), "$pkg.apk").delete()
                    com.nerfherder.walkthor.ScreenMessage.show("$name updated", 2_500)
                    load(context)
                }
                else -> {
                    clearProgress(pkg)
                    File(File(context.cacheDir, "updates"), "$pkg.apk").delete()
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    com.nerfherder.walkthor.ScreenMessage.show("$name wasn't updated" + (msg?.let { ": ${it.take(80)}" } ?: ""), 3_500)
                    load(context)
                }
            }
        }
    }
}
