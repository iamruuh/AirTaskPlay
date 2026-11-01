package app.ruuh.airtaskplay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class PlayerService : MediaSessionService() {
    companion object {
        const val CMD = "app.ruuh.airtaskplay.CMD"        // Tasker -> app
        const val STATUS = "app.ruuh.airtaskplay.STATUS"  // app -> Tasker
        @Volatile var instance: PlayerService? = null
    }
    private class Trk(val id: String, val title: String, val artist: String, val added: Long, val dir: String)

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var server: NanoHTTPD? = null
    private lateinit var audio: AudioManager
    private var all = listOf<Trk>()
    private var db = JSONObject()
    private val artCache = LruCache<String, ByteArray>(40)
    private var active = "guest"
    private val fails = HashMap<String, Pair<Int, Long>>()
    private val tickH = Handler(Looper.getMainLooper())
    private lateinit var air: AirPlayManager
    private val airH = Handler(Looper.getMainLooper())
    private val airExec = Executors.newSingleThreadExecutor()
    private var kickUntil = 0L
    private var airCrashed = false

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        db = try { JSONObject(getSharedPreferences("t", 0).getString("db", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        if (pls().optJSONObject("liked") == null) { pls().put("liked", JSONObject().put("n", "Liked").put("it", JSONArray())); save() }
        active = db.optString("active", "guest")
        instance = this
        if (getSharedPreferences("t", 0).getBoolean("air_boot", false)) {   // native start crashed last time
            db.put("airplay", false); airCrashed = true; save()
            getSharedPreferences("t", 0).edit().putBoolean("air_boot", false).apply()
        }
        air = AirPlayManager(this, { airH.post { broadcast() } }, { airConnected() }, { airStreaming() })
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player.setPauseAtEndOfMediaItems(!db.optBoolean("autoplay", true))
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, e: Player.Events) { broadcast() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && air.busy) airKick()   // playing here disconnects the AirPlay device
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) snap() }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { record(o.mediaItem?.mediaId ?: "", 0L, 0L, true); save() }
            }
        })
        session = MediaSession.Builder(this, player).build()
        tickH.post(object : Runnable { override fun run() { if (player.isPlaying) snap(); tickH.postDelayed(this, 10000) } })
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val m = HashMap<String, String>()
                i.extras?.keySet()?.forEach { k -> m[k] = i.extras!!.get(k).toString() }
                api(m["cmd"] ?: return, m)
            }
        }, IntentFilter(CMD), ContextCompat.RECEIVER_EXPORTED)
        airEnsure()
        for (tryPort in listOf(1826, 1831, 1832, 1833)) { try { server = object : NanoHTTPD(tryPort) {
            override fun serve(r: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val ok = NanoHTTPD.Response.Status.OK
                val resp = when {
                    r.uri == "/" || r.uri == "/index.html" ->
                        NanoHTTPD.newFixedLengthResponse(ok, "text/html", assets.open("index.html").bufferedReader().readText())
                    r.uri == "/logo.png" -> NanoHTTPD.newChunkedResponse(ok, "image/png", assets.open("logo.png"))
                    r.uri == "/api/art" -> {
                        val b = art(r.parms["id"] ?: "", r.parms["s"]?.toIntOrNull() ?: 400)
                        if (b == null) NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
                        else NanoHTTPD.newFixedLengthResponse(ok, "image/jpeg", java.io.ByteArrayInputStream(b), b.size.toLong())
                            .also { it.addHeader("Cache-Control", "public, max-age=86400") }
                    }
                    else -> {
                        val out = try { api(r.uri.removePrefix("/api/"), r.parms) } catch (e: Exception) { JSONObject().put("error", e.toString()).toString() }
                        NanoHTTPD.newFixedLengthResponse(ok, "application/json", out)
                    }
                }
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }
        }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            getSharedPreferences("t", 0).edit().putInt("port", tryPort).apply(); break
        } catch (e: java.io.IOException) { } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val boot = intent?.getBooleanExtra("boot", false) == true
        if (intent?.getBooleanExtra("rescan", false) == true || boot) load()
        keepAlive(boot)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun keepAlive(boot: Boolean = false) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("airtaskplay", "AirTaskPlay server", NotificationManager.IMPORTANCE_LOW))
            val port = getSharedPreferences("t", 0).getInt("port", 0)
            val n = NotificationCompat.Builder(this, "airtaskplay").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AirTaskPlay is running").setContentText(if (port > 0) "Web player on port $port" else "Starting…").setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) {
                val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                try { startForeground(2, n, if (boot) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else both) }
                catch (_: Exception) { startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            } else startForeground(2, n)
        } catch (_: Exception) { }
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        getSharedPreferences("t", 0).edit().putInt("port", 0).apply(); instance = null; airH.removeCallbacksAndMessages(null); air.stop(); airExec.shutdown(); tickH.removeCallbacksAndMessages(null); server?.stop(); session?.release(); player.release(); super.onDestroy()
    }

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val t = FutureTask(f); Handler(Looper.getMainLooper()).post(t); return t.get()
    }

    // ---------- stored data (settings, Liked, playlists) ----------
    private fun save() { getSharedPreferences("t", 0).edit().putString("db", db.toString()).apply() }
    private fun pls(): JSONObject = db.optJSONObject("pls") ?: JSONObject().also { db.put("pls", it) }
    private fun items(pl: String): JSONArray = pls().optJSONObject(pl)?.optJSONArray("it") ?: JSONArray()
    private fun has(pl: String, id: String): Boolean {
        val a = items(pl); for (k in 0 until a.length()) if (a.getJSONObject(k).getString("i") == id) return true; return false
    }
    private fun add(pl: String, id: String) {
        if (id.isNotEmpty() && !has(pl, id)) { items(pl).put(JSONObject().put("i", id).put("t", System.currentTimeMillis())); save() }
    }
    private fun del(pl: String, id: String) {
        val a = items(pl); val n = JSONArray()
        for (k in 0 until a.length()) if (a.getJSONObject(k).getString("i") != id) n.put(a.getJSONObject(k))
        pls().optJSONObject(pl)?.put("it", n); save()
    }
    private fun move(pl: String, id: String, d: Int) {
        val a = items(pl)
        for (k in 0 until a.length()) {
            if (a.getJSONObject(k).getString("i") != id) continue
            val j = k + d; if (j < 0 || j >= a.length()) return
            val x = a.getJSONObject(k); a.put(k, a.getJSONObject(j)); a.put(j, x); save(); return
        }
    }

    // ---------- library ----------
    private fun mi(t: Trk) = MediaItem.Builder().setMediaId(t.id)
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id.toLong()))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build()

    private fun load() {
        val out = ArrayList<Trk>()
        try {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(2)
                    out += Trk(c.getLong(0).toString(), c.getString(1) ?: "Unknown",
                        if (a.isNullOrBlank() || a == "<unknown>") "Unknown artist" else a, c.getLong(3),
                        java.io.File(c.getString(4) ?: "/x").parent ?: "/")
                }
            }
        } catch (_: SecurityException) { }
        all = out
        if (!player.isPlaying && player.mediaItemCount == 0) player.setMediaItems(resolve("all", "title").map { mi(it) })
    }

    private fun visible(): List<Trk> {
        if (db.optString("mode", "all") != "folders") return all
        val f = db.optJSONArray("folders") ?: return emptyList()
        val set = (0 until f.length()).map { f.getString(it) }
        return all.filter { t -> set.any { t.dir == it || t.dir.startsWith("$it/") } }
    }

    private fun resolve(src: String, sort: String): List<Trk> {
        val vis = visible()
        var base: List<Pair<Trk, Long>> = when {
            src.startsWith("artist:") -> vis.filter { it.artist == src.substring(7) }.map { it to it.added }
            src.startsWith("pl:") -> {
                val byId = vis.associateBy { it.id }; val a = items(src.substring(3))
                (0 until a.length()).mapNotNull { k -> val o = a.getJSONObject(k); byId[o.getString("i")]?.let { it to o.getLong("t") } }
            }
            else -> vis.map { it to it.added }
        }
        base = when (sort) {
            "title" -> base.sortedBy { it.first.title.lowercase() }
            "artist" -> base.sortedWith(compareBy({ it.first.artist.lowercase() }, { it.first.title.lowercase() }))
            "new" -> base.sortedByDescending { it.second }
            "old" -> base.sortedBy { it.second }
            else -> base
        }
        return base.map { it.first }
    }

    private fun art(id: String, size: Int): ByteArray? {
        val key = "$id/$size"; artCache.get(key)?.let { return it }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLong()))
            val b = mmr.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1; while (o.outWidth / s / 2 >= size) s *= 2
            val bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val out = java.io.ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray().also { artCache.put(key, it) }
        } catch (e: Exception) { null } finally { mmr.release() }
    }

    // ---------- AirPlay <-> player: whoever starts last wins ----------
    private fun airEnabled() = db.optBoolean("airplay", true)
    private fun airStart() { val nm = airName(); airExec.execute { air.start(nm); airH.post { broadcast() } } }
    private fun airStop() { airExec.execute { air.stop() } }
    private fun airEnsure() { if (airEnabled()) airStart() else airStop() }
    private fun airCycle(): Runnable {
        val nm = airName()
        return Runnable {
            air.stop()
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            if (airEnabled()) air.start(nm)
            airH.post { broadcast() }
        }
    }
    // music started here: silence the AirPlay stream now and disconnect the device by restarting the server
    private fun airKick() { kickUntil = System.currentTimeMillis() + 8000; air.silence(); airExec.execute(airCycle()) }
    // a device connected (native thread): pause the music, ignoring very short probe connections
    private fun airConnected() {
        airH.post {
            if (System.currentTimeMillis() < kickUntil) { airKick(); return@post }   // just disconnected: refuse an instant reconnect
            airH.postDelayed({ if (air.busy) player.pause() }, 1500)
        }
    }
    // a device started streaming audio (native thread): pause the music at once
    private fun airStreaming() {
        airH.post {
            if (System.currentTimeMillis() < kickUntil) { airKick(); return@post }
            player.pause()
        }
    }
    fun airName(): String = db.optString("airname", "AirTaskPlay")
    fun airOn(): Boolean = airEnabled()
    fun airConfigure(on: Boolean, name: String) {
        val n = name.trim().ifEmpty { "AirTaskPlay" }
        val renamed = n != airName()
        db.put("airname", n).put("airplay", on); airCrashed = false; save()
        if (!on) airStop() else if (renamed || !air.running) airExec.execute(airCycle()) else airStart()
    }
    fun airRestart() { airCrashed = false; if (airEnabled()) airExec.execute(airCycle()) }
    fun airStatus(): String {
        if (!airEnabled()) return "Off"
        air.error?.let { return "Could not start: $it" + (if (air.lastLog.isNotEmpty()) "\nLast log: ${air.lastLog}" else "") }
        if (air.running) return if (air.busy) "A device is connected" else "Ready. Look for \"${airName()}\" in the AirPlay list on your iPhone"
        return "Starting…"
    }
    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ---------- profiles, PINs, sessions ----------
    private fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun profs(): JSONObject = db.optJSONObject("profiles") ?: JSONObject().also { db.put("profiles", it) }
    private fun uuid() = java.util.UUID.randomUUID().toString().replace("-", "")
    private fun pinOk(pin: String) = Regex("\\d{4}").matches(pin)
    private fun setPin(pf: JSONObject, pin: String) { val s = uuid(); pf.put("s", s).put("h", sha(s + pin)).put("tk", JSONArray()) }
    private fun newToken(pf: JSONObject): String {
        val t = uuid() + uuid(); val a = pf.optJSONArray("tk") ?: JSONArray(); val n = JSONArray()
        for (i in maxOf(0, a.length() - 9) until a.length()) n.put(a.getString(i))
        n.put(sha(t)); pf.put("tk", n); return t
    }
    private fun tokOk(u: String?, k: String?): Boolean {
        if (u == null || u == "guest") return true
        val pf = profs().optJSONObject(u) ?: return false
        if (k.isNullOrEmpty()) return false
        val a = pf.optJSONArray("tk") ?: return false; val h = sha(k)
        for (i in 0 until a.length()) if (a.getString(i) == h) return true
        return false
    }
    private fun uid(p: Map<String, String>): String = p["u"]?.takeIf { tokOk(it, p["k"]) } ?: "guest"
    private fun login(p: Map<String, String>): String {
        val u = p["u"] ?: ""; val pf = profs().optJSONObject(u); val pin = p["pin"] ?: ""
        if (pf == null || !pinOk(pin)) return "{\"error\":\"bad\"}"
        val (cnt, until) = fails[u] ?: (0 to 0L)
        val now = System.currentTimeMillis()
        if (now < until) return JSONObject().put("error", "locked").put("wait", (until - now) / 1000).toString()
        if (!pf.has("h")) { setPin(pf, pin); fails.remove(u); val k = newToken(pf); save(); return JSONObject().put("ok", true).put("k", k).toString() }
        if (sha(pf.getString("s") + pin) == pf.getString("h")) {
            fails.remove(u); val k = newToken(pf); save(); return JSONObject().put("ok", true).put("k", k).toString()
        }
        val c = cnt + 1
        if (c >= 5) { fails[u] = 0 to now + 300000; return JSONObject().put("error", "locked").put("wait", 300).toString() }
        fails[u] = c to 0L
        return JSONObject().put("error", "wrong").put("left", 5 - c).toString()
    }
    private fun signup(p: Map<String, String>): String {
        val n = p["name"]?.trim().orEmpty(); val pin = p["pin"] ?: ""
        if (n.isEmpty() || !pinOk(pin)) return "{\"error\":\"bad\"}"
        val id = "u" + System.currentTimeMillis().toString(36) + (100..999).random()
        val pf = JSONObject().put("n", n); setPin(pf, pin); profs().put(id, pf)
        val k = newToken(pf); save()
        return JSONObject().put("ok", true).put("id", id).put("name", n).put("k", k).toString()
    }
    // used by the app screen only (same process, not reachable from other apps)
    fun profileList(): List<Pair<String, String>> { val o = profs(); return o.keys().asSequence().map { it to o.getJSONObject(it).optString("n") }.toList() }
    fun resetPin(id: String, pin: String): Boolean {
        val pf = profs().optJSONObject(id) ?: return false
        if (!pinOk(pin)) return false
        setPin(pf, pin); fails.remove(id); save(); return true
    }
    fun deleteProfile(id: String) {
        profs().remove(id); db.optJSONObject("hist")?.remove(id)
        if (active == id) { active = "guest"; db.put("active", "guest") }
        save()
    }

    // ---------- listening history (per profile) ----------
    private fun record(id: String, pos: Long, dur: Long, forceDone: Boolean = false) {
        if (id.isEmpty()) return
        val h = db.optJSONObject("hist") ?: JSONObject().also { db.put("hist", it) }
        val u = h.optJSONObject(active) ?: JSONObject().also { h.put(active, it) }
        val finished = forceDone || (dur > 0 && pos >= dur - 8000)
        if (!finished && pos < 10000) return
        u.put(id, JSONObject().put("p", if (finished) 0L else pos).put("d", dur).put("t", System.currentTimeMillis()).put("f", finished))
        while (u.length() > 1000) {
            val old = u.keys().asSequence().minByOrNull { u.getJSONObject(it).getLong("t") } ?: break
            u.remove(old)
        }
    }
    private fun snap() {
        val id = player.currentMediaItem?.mediaId ?: return
        record(id, player.currentPosition, maxOf(0L, player.duration)); save()
    }

    // ---------- status / api ----------
    private fun status(): JSONObject {
        val md = player.mediaMetadata; val id = player.currentMediaItem?.mediaId ?: ""
        return JSONObject()
            .put("playing", player.isPlaying).put("id", id)
            .put("index", player.currentMediaItemIndex).put("count", player.mediaItemCount)
            .put("title", md.title?.toString() ?: "").put("artist", md.artist?.toString() ?: "")
            .put("pos", player.currentPosition).put("dur", maxOf(0L, player.duration))
            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeat", when (player.repeatMode) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" })
            .put("autoplay", db.optBoolean("autoplay", true))
            .put("liked", id.isNotEmpty() && has("liked", id))
            .put("airplay", JSONObject().put("on", airEnabled()).put("running", air.running).put("busy", air.busy).put("streaming", air.streaming))
    }

    private fun broadcast() {
        val s = status()
        sendBroadcast(Intent(STATUS)
            .putExtra("playing", s.getBoolean("playing")).putExtra("title", s.getString("title"))
            .putExtra("artist", s.getString("artist")).putExtra("index", s.getInt("index")))
    }

    private fun settings(): String {
        val a = JSONArray()
        all.groupingBy { it.dir }.eachCount().toSortedMap().forEach { (d, c) ->
            a.put(JSONObject().put("path", d).put("count", c)
                .put("name", d.removePrefix("/storage/emulated/0").removePrefix("/")))
        }
        return JSONObject().put("mode", db.optString("mode", "all")).put("dirs", a)
            .put("folders", db.optJSONArray("folders") ?: JSONArray())
            .put("version", appVersion()).put("airplay", airEnabled()).put("airStatus", airStatus()).put("airName", airName()).put("airRunning", air.running).put("airFailed", air.failed).put("airCrashed", airCrashed).toString()
    }

    fun api(cmd: String, p: Map<String, String>): String = onMain {
        if (cmd in setOf("play", "playctx", "resume", "pause", "stop", "toggle", "next", "prev", "seek")) {
            val u = p["u"]?.let { if (tokOk(it, p["k"])) it else "guest" }
            if (u != null && u != active) { snap(); active = u; db.put("active", u) }
            else if (cmd in setOf("playctx", "resume", "next", "prev", "stop")) snap()
        }
        val data: String? = when (cmd) {
            "list" -> {
                val lk = HashSet<String>(); val la = items("liked")
                for (k in 0 until la.length()) lk.add(la.getJSONObject(k).getString("i"))
                val a = JSONArray()
                resolve(p["src"] ?: "all", p["sort"] ?: "title").forEach {
                    a.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("l", it.id in lk))
                }
                a.toString()
            }
            "artists" -> JSONArray().also { a ->
                visible().groupingBy { it.artist }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (n, c) -> a.put(JSONObject().put("name", n).put("count", c)) }
            }.toString()
            "playlists" -> JSONArray().also { a ->
                val ids = pls().keys().asSequence().filter { it != "liked" }.sorted().toList()
                for (id in listOf("liked") + ids) {
                    val o = pls().optJSONObject(id) ?: continue
                    a.put(JSONObject().put("id", id).put("name", o.optString("n")).put("count", o.optJSONArray("it")?.length() ?: 0))
                }
            }.toString()
            "history" -> {
                if (p["u"] != null && !tokOk(p["u"], p["k"])) return@onMain "{\"error\":\"auth\"}"
                val u = db.optJSONObject("hist")?.optJSONObject(p["u"] ?: "guest") ?: JSONObject()
                val byId = visible().associateBy { it.id }
                val rows = ArrayList<Pair<Trk, JSONObject>>()
                for (id in u.keys()) { val t = byId[id] ?: continue; rows.add(t to u.getJSONObject(id)) }
                rows.sortByDescending { it.second.getLong("t") }
                val res = JSONArray(); val again = JSONArray()
                for ((t, e) in rows) (if (e.getBoolean("f")) again else res).put(JSONObject().put("id", t.id)
                    .put("title", t.title).put("artist", t.artist).put("p", e.getLong("p")).put("d", e.getLong("d")).put("t", e.getLong("t")))
                JSONObject().put("resume", res).put("again", again).toString()
            }
            "profiles" -> JSONArray().also { a ->
                val o = profs()
                for (k in o.keys()) a.put(JSONObject().put("id", k).put("name", o.getJSONObject(k).optString("n")).put("np", !o.getJSONObject(k).has("h")))
            }.toString()
            "login" -> login(p)
            "signup" -> signup(p)
            "settings" -> settings()
            else -> null
        }
        if (data != null) return@onMain data
        when (cmd) {
            "play" -> { p["index"]?.toIntOrNull()?.let { player.seekTo(it, 0) }; player.prepare(); player.play() }
            "playctx" -> {
                val l = resolve(p["src"] ?: "all", p["sort"] ?: "title")
                val i = l.indexOfFirst { it.id == p["id"] }.coerceAtLeast(0)
                if (l.isNotEmpty()) { player.setMediaItems(l.map { mi(it) }, i, 0L); player.prepare(); player.play() }
            }
            "pause" -> player.pause()
            "stop" -> { player.stop(); player.seekToDefaultPosition() }
            "toggle" -> if (player.isPlaying) player.pause() else { player.prepare(); player.play() }
            "next" -> player.seekToNextMediaItem()
            "prev" -> player.seekToPreviousMediaItem()
            "seek" -> player.seekTo(p["ms"]?.toLongOrNull() ?: 0L)
            "volume" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (p["level"]?.toIntOrNull() ?: 50).coerceIn(0, 100) * max / 100, 0)
            }
            "shuffle" -> player.shuffleModeEnabled = p["on"] == "1"
            "repeat" -> player.repeatMode = when (p["mode"]) { "one" -> Player.REPEAT_MODE_ONE; "all" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
            "autoplay" -> {
                val on = p["on"]?.let { it == "1" } ?: !db.optBoolean("autoplay", true)
                db.put("autoplay", on); player.setPauseAtEndOfMediaItems(!on); save()
            }
            "like" -> {
                val id = p["id"] ?: player.currentMediaItem?.mediaId ?: ""
                val on = p["on"]?.let { it == "1" } ?: !has("liked", id)
                if (on) add("liked", id) else del("liked", id)
            }
            "plcreate" -> {
                val n = p["name"]?.trim().orEmpty()
                if (n.isNotEmpty()) { pls().put("p" + System.currentTimeMillis(), JSONObject().put("n", n).put("it", JSONArray())); save() }
            }
            "pldelete" -> { val id = p["pl"] ?: ""; if (id != "liked") { pls().remove(id); save() } }
            "pladd" -> add(p["pl"] ?: "", p["id"] ?: "")
            "plremove" -> del(p["pl"] ?: "", p["id"] ?: "")
            "plmove" -> move(p["pl"] ?: "", p["id"] ?: "", p["dir"]?.toIntOrNull() ?: 0)
            "setmode" -> { db.put("mode", if (p["mode"] == "folders") "folders" else "all"); save(); load() }
            "folder" -> {
                val cur = db.optJSONArray("folders") ?: JSONArray()
                val s = (0 until cur.length()).map { cur.getString(it) }.toMutableSet()
                if (p["on"] == "1") s.add(p["path"] ?: "") else s.remove(p["path"] ?: "")
                db.put("folders", JSONArray(s.toList())); save(); load()
            }
            "resume" -> {
                val t = all.firstOrNull { it.id == p["id"] }
                if (t != null) {
                    val e = db.optJSONObject("hist")?.optJSONObject(uid(p))?.optJSONObject(t.id)
                    val ms = if (p["start"] == "1" || e == null || e.getBoolean("f")) 0L else maxOf(0L, e.getLong("p") - 3000L)
                    val l = visible().filter { it.dir == t.dir }.sortedBy { it.title.lowercase() }
                    player.setMediaItems(l.map { mi(it) }, l.indexOfFirst { it.id == t.id }.coerceAtLeast(0), ms)
                    player.prepare(); player.play()
                }
            }
            "profile" -> {
                val u = p["u"] ?: ""; val n = p["name"]?.trim().orEmpty()
                if (u != "guest" && n.isNotEmpty() && tokOk(u, p["k"])) { profs().optJSONObject(u)?.put("n", n); save() }
            }
            "histdel" -> { db.optJSONObject("hist")?.optJSONObject(uid(p))?.remove(p["id"] ?: ""); save() }
            "histclear" -> {
                val h = db.optJSONObject("hist")?.optJSONObject(uid(p))
                if (h != null) {
                    val rm = ArrayList<String>()
                    for (k in h.keys()) {
                        val f = h.getJSONObject(k).getBoolean("f"); val c = p["cat"]
                        if (c == "all" || (c == "again" && f) || (c == "resume" && !f)) rm.add(k)
                    }
                    rm.forEach { h.remove(it) }; save()
                }
            }
            "airplay" -> {
                airConfigure(p["on"]?.let { it == "1" } ?: !airEnabled(), airName())
            }
            "airname" -> airConfigure(airEnabled(), p["name"] ?: "AirTaskPlay")
            "airrestart" -> airRestart()
            "rescan" -> load()
        }
        status().toString()
    }
}
