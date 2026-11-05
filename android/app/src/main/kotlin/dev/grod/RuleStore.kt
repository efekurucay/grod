package dev.grod

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The active rules and where they came from. Three sources feed it (bundled asset, adb-pushed file,
 * rules URL); the last valid copy wins and is kept in filesDir so a bad copy never replaces it.
 * Public API and state are main thread only. Reading, parsing and saving copies run in order on one
 * worker thread, and finished results are published back on the main thread.
 */
class RuleStore private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("grod", Context.MODE_PRIVATE)
    private val fires = app.getSharedPreferences("grod.fires", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "grod-rules").apply { isDaemon = true } }
    private val listeners = ArrayList<() -> Unit>()
    private val copy = ActiveCopy(File(app.filesDir, "rules.json"))
    private var lastPushedCheck = 0L
    private var fetching = false

    /** Resolved on the worker because getExternalFilesDir creates the directory on first use. */
    @Volatile
    private var pushedFile: File? = null

    @Volatile
    private var channelFile: ChannelFile? = null

    /** The active rules copy, before the phone's own channels are added. */
    private var parsed: RuleSet = RuleSet.EMPTY
    private var localChannels: List<Channel> = emptyList()
    private var channelsMtime = -1L

    /** Active rules plus the channels blocked from this phone. */
    var rules: RuleSet = RuleSet.EMPTY
        private set

    val revision: String get() = rules.revision ?: "unknown"
    val source: String get() = prefs.getString(K_SOURCE, null) ?: "none"
    val lastError: String? get() = prefs.getString(K_ERROR, null)
    val lastFetchAt: Long get() = prefs.getLong(K_FETCHED_AT, 0L)

    var rulesUrl: String?
        get() = prefs.getString(K_URL, null)
        set(value) {
            val url = value?.trim()?.takeIf { it.isNotEmpty() }
            if (url == rulesUrl) return
            // A new URL's content is unrelated to the old ETag.
            prefs.edit().putString(K_URL, url).remove(K_ETAG).remove(K_FETCHED_AT).apply()
        }

    init {
        worker.execute {
            val external = app.getExternalFilesDir(null)
            pushedFile = external?.let { File(it, "rules.json") }
            channelFile = external?.let { ChannelFile(File(it, "channels-added.json")) }
            val loaded = copy.load()
            // Queued before any copy the worker applies later, so it cannot overwrite a newer one.
            main.post {
                if (loaded != null) {
                    parsed = loaded
                    publishRules()
                }
                checkChannels()
            }
        }
        checkBundled()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Bundled copy (first run, app update), the adb-pushed file, and the phone's channel list. */
    fun checkLocal() {
        checkBundled()
        checkPushed()
        checkChannels()
    }

    /** Cheap enough to call on every accessibility event: both checks are rate limited. */
    fun onEvent() {
        val now = System.currentTimeMillis()
        if (now - lastPushedCheck >= PUSHED_CHECK_MS) {
            checkPushed()
            checkChannels()
        }
        if (rulesUrl != null && now - lastFetchAt >= FETCH_INTERVAL_MS) fetch()
    }

    /** Downloads the rules URL in the background, if one is set. */
    fun fetch() {
        val url = rulesUrl ?: return
        if (fetching) return
        fetching = true
        prefs.edit().putLong(K_FETCHED_AT, System.currentTimeMillis()).apply()
        val etag = prefs.getString(K_ETAG, null)
        // Its own thread so a slow server never delays a pushed copy waiting on the worker.
        thread(name = "grod-fetch", isDaemon = true) {
            val result = RuleFetch.get(url, etag)
            main.post {
                fetching = false
                if (url != rulesUrl) return@post
                when (result) {
                    FetchResult.NotModified -> setError(null)
                    is FetchResult.Body -> ingest("url", result.etag) { result.text }
                    is FetchResult.Failed -> setError(result.message)
                }
            }
        }
    }

    /**
     * Blocks a channel from this phone. [done] gets false when it is already blocked. The file is
     * read again before writing, since `grod sync` may have replaced it.
     */
    fun addLocalChannel(channel: Channel, done: (added: Boolean) -> Unit, failed: (String) -> Unit) {
        if (rules.channels.any { sameHandle(it.channel.handle, channel.handle) }) return done(false)
        val file = channelFile ?: return failed("phone storage is not available")
        worker.execute {
            val list = try {
                file.add(channel)
            } catch (e: Exception) {
                main.post { failed(e.message ?: e.javaClass.simpleName) }
                return@execute
            }
            main.post {
                setLocalChannels(list, file)
                done(true)
            }
        }
    }

    fun removeLocalChannel(handle: String) {
        val file = channelFile ?: return
        worker.execute {
            val list = try {
                file.remove(handle)
            } catch (e: Exception) {
                main.post { setError("channels file: ${e.message}") }
                return@execute
            }
            main.post { setLocalChannels(list, file) }
        }
    }

    fun fireCount(ruleId: String): Int = fires.getInt(ruleId, 0)

    fun countFire(ruleId: String) {
        fires.edit().putInt(ruleId, fireCount(ruleId) + 1).apply()
        notifyListeners()
    }

    private fun checkBundled() = worker.execute {
        @Suppress("DEPRECATION")
        val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        if (copy.exists && stamp == prefs.getLong(K_BUNDLED_STAMP, -1L)) return@execute
        prefs.edit().putLong(K_BUNDLED_STAMP, stamp).apply()
        save("bundled", null) { app.assets.open("rules.json").use { it.readBytes().toString(Charsets.UTF_8) } }
    }

    /** Only a stat on the main thread; the file is read on the worker. A missing file means no channels. */
    private fun checkChannels() {
        val file = channelFile ?: return
        val mtime = file.file.lastModified()
        if (mtime == channelsMtime) return
        channelsMtime = mtime
        worker.execute {
            val list = try {
                file.read()
            } catch (e: Exception) {
                main.post { setError("channels file ignored: ${e.message}") }
                return@execute
            }
            main.post { setLocalChannels(list, file) }
        }
    }

    private fun setLocalChannels(list: List<Channel>, file: ChannelFile) {
        channelsMtime = file.file.lastModified()
        if (list == localChannels) return
        localChannels = list
        publishRules()
    }

    private fun publishRules() {
        rules = parsed.copy(localChannels = localChannels)
        notifyListeners()
    }

    /** Only a stat on the main thread; reading the file happens on the worker. */
    private fun checkPushed() {
        lastPushedCheck = System.currentTimeMillis()
        val file = pushedFile ?: return
        val mtime = file.lastModified()
        if (mtime == 0L || mtime == prefs.getLong(K_PUSHED_MTIME, 0L)) return
        prefs.edit().putLong(K_PUSHED_MTIME, mtime).apply()
        ingest("adb", null) { file.readText() }
    }

    private fun ingest(from: String, etag: String?, read: () -> String) = worker.execute { save(from, etag, read) }

    /** Worker thread: reads, validates and saves a copy, then publishes it or the error on main. */
    private fun save(from: String, etag: String?, read: () -> String) {
        val saved = try {
            copy.replace(read())
        } catch (e: InvalidRulesException) {
            main.post { setError("$from copy rejected: ${e.message}") }
            return
        } catch (e: IOException) {
            main.post { setError("$from copy not saved: ${e.message ?: e.javaClass.simpleName}") }
            return
        }
        main.post {
            parsed = saved
            prefs.edit().apply {
                putString(K_SOURCE, from)
                remove(K_ERROR)
                // The ETag vouches for the body on disk; once another source replaces it, a 304 must not keep that body.
                if (from == "url" && etag != null) putString(K_ETAG, etag) else remove(K_ETAG)
            }.apply()
            publishRules()
        }
    }

    private fun setError(message: String?) {
        prefs.edit().putString(K_ERROR, message).apply()
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l()
    }

    companion object {
        private const val PUSHED_CHECK_MS = 5_000L
        private const val FETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val K_SOURCE = "source"
        private const val K_ERROR = "lastError"
        private const val K_URL = "rulesUrl"
        private const val K_ETAG = "etag"
        private const val K_FETCHED_AT = "lastFetchAt"
        private const val K_BUNDLED_STAMP = "bundledStamp"
        private const val K_PUSHED_MTIME = "pushedMtime"

        private var instance: RuleStore? = null

        /** Shared by the service and the activity, which run in the same process on the main thread. */
        fun get(context: Context): RuleStore =
            instance ?: RuleStore(context.applicationContext).also { instance = it }
    }
}
