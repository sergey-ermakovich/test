package com.zeekrprobe.netinfo

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Standalone phone helper for sideloading over Wi-Fi ADB. It shows, in plain terms:
 *   - this phone's own IPv4 on each interface;
 *   - the default gateway (= the IP of whoever shares internet to this phone);
 *   - every peer this phone can see (ARP table) — when the phone is the hotspot, the
 *     car shows up here — and which of them answer on the ADB port 5555.
 * From that the right `adb connect <ip>:5555` line is obvious.
 */
class NetActivity : Activity() {

    private lateinit var out: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var adbPort = 5555
    @Volatile private var lastText = ""

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(button("Обновить") { refresh() })
        bar.addView(button("Найти машину") { refresh(scan = true) })
        bar.addView(button("Копировать") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("netinfo", lastText))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        })
        root.addView(bar)

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 230, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(true)
            text = "…"
        }
        val scroll = ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val hint = TextView(this).apply {
            text = "Телефон подключён к хотспоту машины (ZEEKR-…). Жми «Найти машину» — " +
                "перебор подсети найдёт адрес с открытым ADB (помечен ✓). Его вводи в Bugjaeger. " +
                "Если VPN включён — выключи его на время."
            setTextColor(Color.rgb(150, 160, 180))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.START
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(hint)
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh(scan: Boolean = false) {
        io.execute {
            val text = build(scan)
            lastText = text
            ui.post { out.text = text }
        }
    }

    private fun build(scan: Boolean): String {
        val sb = StringBuilder()
        val wifiIps = ArrayList<String>()   // IPv4 on wlan-like interfaces, where the car is
        var hasVpn = false

        sb.append("ТВОЙ ТЕЛЕФОН\n")
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        sb.append("  ").append(ni.name).append("  ").append(ip).append('\n')
                        val n = ni.name.lowercase()
                        if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("ipsec")) hasVpn = true
                        else if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) wifiIps.add(ip)
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("  не удалось: ").append(t.javaClass.simpleName).append('\n')
        }
        if (wifiIps.isEmpty()) sb.append("  нет Wi-Fi IPv4 — подключись к хотспоту машины\n")

        if (hasVpn) {
            sb.append("\n⚠ ВКЛЮЧЁН VPN (tun0)\n")
            sb.append("  Он может перехватывать трафик и мешать достучаться до машины.\n")
            sb.append("  Если подключение не идёт — выключи VPN и сканируй снова.\n")
        }

        if (!scan) {
            sb.append("\nЖми «Найти машину» — телефон переберёт свою Wi-Fi подсеть\n")
            sb.append("и покажет, у кого открыт ADB-порт 5555.\n")
            // Still show the obvious candidate immediately.
            for (ip in wifiIps) {
                val gw = subnetBase(ip) + ".1"
                sb.append("\nВероятный адрес машины: ").append(gw)
                sb.append("\n  adb connect ").append(gw).append(':').append(adbPort).append('\n')
            }
            return sb.toString()
        }

        // Active scan: walk the whole /24 of each Wi-Fi interface for an open 5555.
        // This finds the car without needing ARP or a default route.
        sb.append("\nСКАНИРУЮ ПОДСЕТЬ…\n")
        val found = ArrayList<String>()
        for (ip in wifiIps) {
            val base = subnetBase(ip)
            sb.append("  ").append(base).append(".1–254 (порт ").append(adbPort).append(")\n")
            found += scanSubnet(base, ip)
        }

        sb.append("\nНАЙДЕНА МАШИНА (ADB открыт)\n")
        if (found.isEmpty()) {
            sb.append("  никого с открытым 5555.\n")
            sb.append("  • выключи VPN, если включён;\n")
            sb.append("  • на части телефонов хотспот изолирует клиентов — тогда наоборот:\n")
            sb.append("    раздай Wi-Fi с ТЕЛЕФОНА и подключи к нему машину;\n")
            sb.append("  • либо ADB у машины слушает только локально — тогда обновляйся\n")
            sb.append("    кнопкой «Обновить APK» в самом ZeekrProbe, сеть не нужна.\n")
        } else {
            for (ip in found) {
                sb.append("  ✓ ").append(ip).append('\n')
                sb.append("    adb connect ").append(ip).append(':').append(adbPort).append('\n')
            }
            sb.append("\nЭтот адрес вводи в Bugjaeger (Connect → ").append(found.first()).append(':').append(adbPort).append(").\n")
        }
        return sb.toString()
    }

    /** First three octets of an IPv4, e.g. 192.168.225.147 → 192.168.225. */
    private fun subnetBase(ip: String): String = ip.substringBeforeLast('.')

    /** Scans base.1..254:port in parallel, skipping our own address. Returns open hosts. */
    private fun scanSubnet(base: String, selfIp: String): List<String> {
        val pool = Executors.newFixedThreadPool(64)
        val hits = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tasks = (1..254).mapNotNull { i ->
            val ip = "$base.$i"
            if (ip == selfIp) null else java.util.concurrent.Callable {
                if (portOpen(ip, adbPort)) hits.add(ip)
            }
        }
        try {
            pool.invokeAll(tasks, 20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            pool.shutdownNow()
        }
        return hits.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 500); true }
    } catch (_: Throwable) {
        false
    }
}
