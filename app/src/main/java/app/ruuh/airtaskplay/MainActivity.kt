package app.ruuh.airtaskplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/** Three full-screen pages: Player (the web player), Profiles and AirPlay. Dark, flat glass, neon lime. */
class MainActivity : Activity() {
    private val BG = 0xFF0B0D0A.toInt()
    private val CARD = 0xFF141813.toInt()
    private val LINE = 0xFF262C23.toInt()
    private val LIME = 0xFFB6F000.toInt()
    private val AMBER = 0xFFFFC83D.toInt()
    private val RED = 0xFFFF5468.toInt()
    private val TXT = 0xFFF2F5EE.toInt()
    private val MUT = 0xFF8A9382.toInt()
    private val PAL = intArrayOf(0xFFB6F000.toInt(), 0xFF3DDC97.toInt(), 0xFFFFC83D.toInt(), 0xFF4DA3FF.toInt(), 0xFFFF6B8A.toInt(), 0xFFB388FF.toInt())
    private val DARK = android.R.style.Theme_Material_Dialog_Alert
    private val h = Handler(Looper.getMainLooper())

    private lateinit var addr: TextView
    private lateinit var web: WebView
    private lateinit var wait: TextView
    private lateinit var profBox: LinearLayout
    private lateinit var airSw: Switch
    private lateinit var wakeSw: Switch
    private lateinit var airNameEt: EditText
    private lateinit var airStatus: TextView
    private lateinit var airDot: View
    private val pages = ArrayList<View>()
    private val tabs = ArrayList<TextView>()
    private var cur = 0
    private var webPort = 0
    private var loadFailed = false
    private var airInit = false
    private var profSig = ""

    // ---------- small view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun box(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun btn(t: String, filled: Boolean, color: Int = LIME, click: () -> Unit) = tv(t, 14f, if (filled) BG else color, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(18), dp(11), dp(18), dp(11))
        background = if (filled) box(color, 0, 20) else box(0x00000000, (color and 0x00FFFFFF) or 0x66000000, 20)
        setOnClickListener { click() }
    }
    private fun card(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = box(CARD, LINE, 20); setPadding(dp(18), dp(16), dp(18), dp(16))
        v.forEach { addView(it) }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun field() = EditText(this).apply {
        setTextColor(TXT); setHintTextColor(MUT); setSingleLine(); background = box(0x0FFFFFFF, LINE, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12)); filters = arrayOf(InputFilter.LengthFilter(32))
    }
    private fun sw(label: String, sub: String, sw: Switch): LinearLayout {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(LIME, MUT))
        sw.trackTintList = ColorStateList(states, intArrayOf(0x66B6F000, 0x33FFFFFF))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv(label, 16f, TXT, true)); addView(tv(sub, 12f, MUT)) }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(sw)
        }
    }
    private fun scroll(vararg v: View) = ScrollView(this).apply {
        clipToPadding = false; setPadding(dp(14), dp(2), dp(14), dp(24))
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } })
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun ip(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "phone-ip"
    } catch (_: Exception) { "phone-ip" }
    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ---------- screen ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val logo = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeStream(assets.open("logo.png"))) }
        addr = tv("starting…", 12f, MUT).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); background = box(0x14FFFFFF, 0x22FFFFFF, 16)
            setOnClickListener { copyAddr() }
        }
        val info = tv("i", 15f, TXT, true).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            background = box(0x14FFFFFF, 0x33FFFFFF, 17); setOnClickListener { credits() }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(12), dp(6))
            addView(logo, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(tv("AirTaskPlay", 19f, LIME, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(addr)
            addView(info, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(8) })
        })

        val seg = LinearLayout(this).apply { background = box(CARD, LINE, 22); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        listOf("Player", "Profiles", "AirPlay").forEachIndexed { i, name ->
            val t = tv(name, 14f, MUT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { show(i) } }
            tabs += t; seg.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(seg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(14), dp(6), dp(14), dp(8)) })

        val area = FrameLayout(this)
        root.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // page 1: the web player, full screen
        wait = tv("Starting server…", 15f, MUT).apply { gravity = Gravity.CENTER }
        web = WebView(this).apply {
            setBackgroundColor(BG); visibility = View.INVISIBLE
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { loadFailed = false }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) { loadFailed = true; webPort = 0; v.visibility = View.INVISIBLE }
                }
                override fun onPageFinished(v: WebView, url: String?) { if (!loadFailed) v.visibility = View.VISIBLE }
            }
        }
        pages += FrameLayout(this).apply {
            addView(wait, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // page 2: profiles
        profBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages += scroll(
            tv("Profiles", 24f, TXT, true).apply { setPadding(dp(4), dp(8), 0, 0) },
            tv("Reset a forgotten PIN or remove a profile. This can only be done here, on the phone.", 13f, MUT).apply { setPadding(dp(4), dp(4), dp(4), 0) },
            profBox)

        // page 3: AirPlay
        airSw = Switch(this); wakeSw = Switch(this)
        airNameEt = field().apply { hint = "AirTaskPlay" }
        airStatus = tv("", 14f, TXT)
        airDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUT) } }
        airSw.setOnClickListener { PlayerService.instance?.airConfigure(airSw.isChecked, airNameEt.text.toString()) }
        wakeSw.setOnClickListener {
            getSharedPreferences("t", 0).edit().putBoolean("air_wake", wakeSw.isChecked).apply()
            PlayerService.instance?.airRestart()
        }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(airDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
            addView(airStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        pages += scroll(
            tv("AirPlay", 24f, TXT, true).apply { setPadding(dp(4), dp(8), 0, 0) },
            tv("Play audio from an iPhone or Mac straight to this phone.", 13f, MUT).apply { setPadding(dp(4), dp(4), dp(4), 0) },
            card(sw("AirPlay speaker", "Audio only. Always on, starts with the phone.", airSw)).apply { },
            card(tv("Name shown on iPhone", 12f, MUT), airNameEt.apply { }, btn("Save name", true) {
                PlayerService.instance?.airConfigure(airSw.isChecked, airNameEt.text.toString()); toast("Saved")
            }.apply { }).also { c -> (c.getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8) }; (c.getChildAt(2).layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12) } },
            card(statusRow, btn("Restart AirPlay", false) { PlayerService.instance?.airRestart(); toast("Restarting AirPlay…") }.apply { }),
            card(sw("Stay reachable with the screen off", "Uses more battery. Turn off if AirPlay is not needed all day.", wakeSw)),
            card(tv("How it works", 15f, TXT, true),
                tv("On an iPhone, open Control Center, tap AirPlay and pick the name above.\n\nPlaying music here disconnects the device. A device that connects pauses the music here.", 13f, MUT).apply { setPadding(0, dp(6), 0, 0) }))
        // spacing between cards on the AirPlay page
        (pages[2] as ScrollView).getChildAt(0).let { c -> val g = c as LinearLayout; for (i in 2 until g.childCount) (g.getChildAt(i).layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) }

        pages.forEach { area.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        setContentView(root)
        show(0)
        wakeSw.isChecked = getSharedPreferences("t", 0).getBoolean("air_wake", true)

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) { perms += Manifest.permission.READ_MEDIA_AUDIO; perms += Manifest.permission.POST_NOTIFICATIONS }
        else perms += Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(perms.toTypedArray(), 1)
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val port = getSharedPreferences("t", 0).getInt("port", 0)
            addr.text = if (port == 0) "starting…" else "${ip()}:$port"
            if (port != 0 && webPort != port) { webPort = port; web.loadUrl("http://127.0.0.1:$port/") }
            if (cur == 1) refreshProfiles(false)
            if (cur == 2) {
                if (!airInit) initAir()
                PlayerService.instance?.let { s ->
                    val t = s.airStatus(); airStatus.text = t
                    val c = when { t.startsWith("Could not") -> RED; t.startsWith("Ready") || t.startsWith("A device") -> LIME; t == "Off" -> MUT; else -> AMBER }
                    (airDot.background as GradientDrawable).setColor(c)
                }
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun show(i: Int) {
        cur = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) BG else MUT)
            t.background = if (k == i) box(LIME, 0, 18) else box(0x00000000, 0, 18)
        }
        if (i == 1) refreshProfiles(true)
        if (i == 2) initAir()
    }

    private fun initAir() {
        val svc = PlayerService.instance ?: return
        if (airInit) return
        airInit = true
        airSw.isChecked = svc.airOn(); airNameEt.setText(svc.airName())
    }

    // ---------- profiles ----------
    private fun refreshProfiles(force: Boolean) {
        val svc = PlayerService.instance
        val list = svc?.profileList() ?: emptyList()
        val sig = (svc != null).toString() + list.joinToString("|") { it.first + ":" + it.second }
        if (!force && sig == profSig) return
        profSig = sig
        profBox.removeAllViews()
        if (svc == null) { profBox.addView(card(tv("Server is still starting…", 14f, MUT)), lp(14)); return }
        if (list.isEmpty()) { profBox.addView(card(tv("No profiles yet. Create one from the player on first launch.", 14f, MUT)), lp(14)); return }
        list.forEach { p ->
            val color = PAL[(p.first.fold(7) { a, c -> a * 31 + c.code } and 0x7fffffff) % PAL.size]
            val avatar = tv(p.second.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?", 18f, BG, true).apply {
                gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(tv(p.second, 17f, TXT, true).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            val actions = LinearLayout(this).apply {
                gravity = Gravity.END; setPadding(0, dp(12), 0, 0)
                addView(btn("Reset PIN", false) { resetPin(p) })
                addView(btn("Delete", false, RED) { confirmDelete(p) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            }
            profBox.addView(card(row, actions), lp(12))
        }
    }

    private fun resetPin(p: Pair<String, String>) {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4)); hint = "New 4-digit PIN"; gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this, DARK).setTitle("New PIN for ${p.second}").setView(e)
            .setPositiveButton("Save") { _, _ ->
                val ok = PlayerService.instance?.resetPin(p.first, e.text.toString()) == true
                toast(if (ok) "PIN reset. ${p.second} is signed out of all browsers." else "The PIN must be 4 digits")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(p: Pair<String, String>) {
        AlertDialog.Builder(this, DARK).setMessage("Delete ${p.second} and all of their history?")
            .setPositiveButton("Delete") { _, _ -> PlayerService.instance?.deleteProfile(p.first); refreshProfiles(true) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- misc ----------
    private fun copyAddr() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AirTaskPlay", "http://" + addr.text))
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
    }

    private fun credits() {
        val s = SpannableString("AirTaskPlay ${version()}\n\nMade with help of ♥ Claude by Ruuh\n\nAirPlay audio: UxPlay and android-airplay-server by jqssun (GPL-3.0)")
        val i = s.indexOf('♥')
        s.setSpan(ForegroundColorSpan(RED), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        AlertDialog.Builder(this, DARK).setMessage(s).setPositiveButton("OK", null).show()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        startService(Intent(this, PlayerService::class.java).putExtra("rescan", true))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cur != 0) show(0) else if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
