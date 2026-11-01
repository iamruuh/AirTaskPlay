package app.ruuh.airtaskplay

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.pow

/**
 * Audio-only AirPlay receiver. Uses the UxPlay based native library from
 * github.com/jqssun/android-airplay-server (GPL-3.0). Mirroring and video are not advertised.
 */
class AirPlayManager(
    private val ctx: Context,
    private val onChange: () -> Unit,
    private val onConnect: () -> Unit,
    private val onStream: () -> Unit
) : RaopCallbackHandler, LogListener {
    private val prefs = ctx.getSharedPreferences("t", 0)
    private val conns = AtomicInteger(0)
    @Volatile private var handle = 0L
    @Volatile private var gain = 1f
    @Volatile var failed = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var lastLog = ""
        private set
    @Volatile var port = 0
        private set
    @Volatile var streaming = false
        private set
    private var nsd: NsdManager? = null
    private val regs = ArrayList<NsdManager.RegistrationListener>()
    private var mc: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null

    val running: Boolean get() = handle != 0L
    val busy: Boolean get() = conns.get() > 0

    @Synchronized
    fun start(name: String): Boolean {
        if (handle != 0L) return true
        error = null
        try {
            // crash marker: if native code kills the process while starting, the service turns AirPlay off next launch
            prefs.edit().putBoolean("air_boot", true).commit()
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            NativeBridge.nativeSetDefaultStreamValues(
                am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0)
            val pem = ctx.filesDir.resolve("airplay.pem").absolutePath
            for (want in intArrayOf(7000, 7001, 7002)) {
                // nohold=false: while one device is connected, other devices are refused
                val h = NativeBridge.nativeInit(this, hwAddr(), name, pem, false, false)
                if (h == 0L) break
                NativeBridge.nativeServerAudioConfigure(h, 0, 95, 0, false, false, true, true, false)
                NativeBridge.nativeServerAudioSetClientGain(h, gain)
                NativeBridge.nativeSetH265Enabled(h, false)
                NativeBridge.nativeSetCodecs(h, false, true)
                NativeBridge.nativeSetHlsEnabled(h, false)
                NativeBridge.nativeSetLang(h, java.util.Locale.getDefault().toLanguageTag())
                NativeBridge.nativeSetFeature(h, NativeBridge.FT_MIRROR, false)
                NativeBridge.nativeSetFeature(h, NativeBridge.FT_AUDIO, true)
                NativeBridge.nativeSetPlist(h, "maxFPS", 30)
                NativeBridge.nativeSetPlist(h, "overscanned", 0)
                NativeBridge.nativeSetDisplaySize(h, 1280, 720, 30)
                val got = NativeBridge.nativeStart(h, want)
                if (got >= 0) {
                    handle = h
                    port = got
                    register(h, name, got)
                    acquireLocks()
                    failed = false
                    prefs.edit().putBoolean("air_boot", false).apply()
                    onChange()
                    return true
                }
                NativeBridge.nativeDestroy(h)
            }
            failed = true
            error = "could not open port 7000, 7001 or 7002"
        } catch (t: Throwable) {
            Log.e(TAG, "AirPlay start failed", t)
            failed = true
            error = t.javaClass.simpleName + ": " + (t.message ?: "")
            try { stop() } catch (_: Throwable) {}
        }
        prefs.edit().putBoolean("air_boot", false).apply()
        onChange()
        return false
    }

    @Synchronized
    fun stop() {
        val h = handle
        if (h == 0L && regs.isEmpty()) return
        handle = 0L
        port = 0
        streaming = false
        for (l in regs) { try { nsd?.unregisterService(l) } catch (_: Exception) {} }
        regs.clear()
        if (h != 0L) {
            try { NativeBridge.nativeServerAudioStop(h) } catch (_: Throwable) {}
            try { NativeBridge.nativeStop(h); NativeBridge.nativeDestroy(h) } catch (_: Throwable) {}
        }
        mc?.let { try { it.release() } catch (_: Exception) {} }; mc = null
        wake?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }; wake = null
        conns.set(0)
        onChange()
    }

    /** Silence the AirPlay stream right away (used before the device is disconnected). */
    fun silence() {
        streaming = false
        val h = handle; if (h != 0L) try { NativeBridge.nativeServerAudioStop(h) } catch (_: Throwable) {}
    }

    private fun register(h: Long, name: String, p: Int) {
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(h) ?: emptyMap()
        val airTxt = NativeBridge.nativeGetAirplayTxtRecords(h) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(h) ?: "AirPlay"
        val srvName = NativeBridge.nativeGetServerName(h) ?: name
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        listOf(Triple(raopName, "_raop._tcp", raopTxt), Triple(srvName, "_airplay._tcp", airTxt)).forEach { (n, type, txt) ->
            val info = NsdServiceInfo().apply {
                serviceName = n; serviceType = type; this.port = p
                txt.forEach { (k, v) -> setAttribute(k, v) }
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "$type registered") }
                override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) { Log.e(TAG, "$type registration failed: $c") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            regs += l
        }
    }

    private fun acquireLocks() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mc = wifi.createMulticastLock("airtaskplay_airplay").apply { setReferenceCounted(false); acquire() }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (prefs.getBoolean("air_wake", true)) wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airtaskplay:airplay").apply { acquire() }
    }

    // ---- stable hardware address (AirPlay identity), as in the upstream app ----
    private fun usable(m: ByteArray?) = m != null && m.size == 6 && m.any { it != 0.toByte() }
    private fun hwAddr(): ByteArray {
        try {
            for (i in NetworkInterface.getNetworkInterfaces()) {
                if (i.name.startsWith("wlan") || i.name.startsWith("eth")) { val m = i.hardwareAddress; if (usable(m)) return m!! }
            }
        } catch (_: Exception) {}
        prefs.getString("air_mac", null)?.let { s ->
            try { val b = s.split(":").map { it.toInt(16).toByte() }.toByteArray(); if (usable(b)) return b } catch (_: Exception) {}
        }
        val m = ByteArray(6).also { SecureRandom().nextBytes(it) }
        m[0] = ((m[0].toInt() and 0xF0) or 0x0A).toByte()
        prefs.edit().putString("air_mac", m.joinToString(":") { "%02x".format(it) }).apply()
        return m
    }

    // ---- native callbacks (called from native threads) ----
    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        val h = handle; if (h == 0L) return
        NativeBridge.nativeServerAudioStart(h)
        NativeBridge.nativeServerAudioFormat(h, ct, spf)
        streaming = true
        onStream()
    }
    override fun onConnectionInit() { conns.incrementAndGet(); onChange(); onConnect() }
    override fun onConnectionDestroy() {
        if (conns.decrementAndGet() <= 0) {
            conns.set(0)
            streaming = false
            val h = handle; if (h != 0L) NativeBridge.nativeServerAudioStop(h)
        }
        onChange()
    }
    override fun onClientVolume(db: Float) {
        gain = if (db <= -144f) 0f else 10f.pow(db / 20f)
        val h = handle; if (h != 0L) NativeBridge.nativeServerAudioSetClientGain(h, gain)
    }
    override fun onClientVolumeQuery(): Float = if (gain <= 0f) -144f else (20f * log10(gain)).coerceIn(-30f, 0f)
    // the native library binds this method on the handler object; it must exist
    override fun onLog(msg: String) { lastLog = msg.take(160); Log.i(TAG, msg) }
    override fun onAudioTeardown() { streaming = false }
    override fun onConnectionReset(reason: Int) {}
    override fun onDisplayPin(pin: String) {}
    override fun onMetadata(data: ByteArray) {}
    override fun onCoverArt(data: ByteArray) {}
    override fun onProgress(start: Long, curr: Long, end: Long) {}
    override fun onDacpId(dacpId: String, activeRemote: String) {}
    override fun onMirrorRunning(running: Boolean) {}
    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {}
    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {}
    override fun onVideoPlay(location: String, startPositionSeconds: Float) {}
    override fun onVideoScrub(positionSeconds: Float) {}
    override fun onVideoRate(rate: Float) {}
    override fun onVideoStop() {}
    override fun onVideoSessionPoll() {}

    companion object { private const val TAG = "AirPlayManager" }
}
