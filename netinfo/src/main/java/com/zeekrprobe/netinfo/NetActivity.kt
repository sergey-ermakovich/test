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
import java.io.File
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
        bar.addView(button("Сканировать :5555") { refresh(scan = true) })
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
            text = "Включи раздачу Wi-Fi на телефоне, подключи к ней машину (или наоборот). " +
                "Машина появится в «Соседних устройствах». Жми «Сканировать :5555» — " +
                "у кого ADB открыт, тот помечен ✓. Его адрес вводи в Bugjaeger."
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
        val myIps = ArrayList<String>()

        sb.append("ТВОЙ ТЕЛЕФОН\n")
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        sb.append("  ").append(ni.name).append("  ").append(addr.hostAddress).append('\n')
                        myIps.add(addr.hostAddress)
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("  не удалось: ").append(t.javaClass.simpleName).append('\n')
        }
        if (myIps.isEmpty()) sb.append("  нет активных IPv4 — Wi-Fi выключен?\n")

        val gw = defaultGateway()
        sb.append("\nШЛЮЗ (кто раздаёт тебе интернет)\n")
        sb.append("  ").append(gw ?: "нет — значит, раздаёшь ты сам").append('\n')

        val peers = arpPeers()
        sb.append("\nСОСЕДНИЕ УСТРОЙСТВА (").append(peers.size).append(")\n")
        if (peers.isEmpty()) {
            sb.append("  пусто. Если ты раздаёшь Wi-Fi — убедись, что машина к тебе подключилась.\n")
            sb.append("  (на части телефонов список соседей закрыт системой — тогда смотри шлюз выше)\n")
        }
        // The peer worth connecting to is almost always the one that isn't the gateway
        // (the gateway is the router/hotspot itself). Mark ADB-open ones when scanning.
        for (p in peers) {
            sb.append("  ").append(p.ip)
            if (p.ip == gw) sb.append("  (шлюз)")
            sb.append("  ").append(p.mac).append("  ").append(p.iface)
            if (scan) sb.append(if (portOpen(p.ip, adbPort)) "  ✓ ADB:5555 ОТКРЫТ" else "  — 5555 закрыт")
            sb.append('\n')
        }

        sb.append("\nЧТО ВВОДИТЬ В BUGJAEGER\n")
        val candidates = LinkedHashSet<String>()
        if (gw != null) candidates.add(gw)
        peers.forEach { if (it.ip != gw) candidates.add(it.ip) }
        if (candidates.isEmpty()) {
            sb.append("  (адрес машины появится, когда устройства увидят друг друга)\n")
        } else {
            for (ip in candidates) {
                sb.append("  adb connect ").append(ip).append(':').append(adbPort)
                if (scan && portOpen(ip, adbPort)) sb.append("   ← этот, ADB открыт")
                sb.append('\n')
            }
        }
        if (!scan) sb.append("\n(нажми «Сканировать :5555», чтобы увидеть, у кого ADB реально открыт)\n")
        return sb.toString()
    }

    private class Peer(val ip: String, val mac: String, val iface: String)

    /** Reads /proc/net/arp — the kernel's neighbour table. Readable without root on most builds. */
    private fun arpPeers(): List<Peer> {
        val list = ArrayList<Peer>()
        try {
            File("/proc/net/arp").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6 && parts[0] != "IP") {
                    val ip = parts[0]
                    val mac = parts[3]
                    val iface = parts[5]
                    // 00:00:00:00:00:00 means incomplete/stale entry.
                    if (mac != "00:00:00:00:00:00" && ip.count { it == '.' } == 3) {
                        list.add(Peer(ip, mac, iface))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    /** Default-route gateway from /proc/net/route (hex, little-endian). */
    private fun defaultGateway(): String? {
        try {
            File("/proc/net/route").readLines().drop(1).forEach { line ->
                val f = line.split(Regex("\\s+"))
                if (f.size > 2 && f[1] == "00000000") {
                    val hex = f[2]
                    if (hex.length == 8) {
                        val b = hex.chunked(2).map { it.toInt(16) }
                        return "${b[3]}.${b[2]}.${b[1]}.${b[0]}"
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 600); true }
    } catch (_: Throwable) {
        false
    }
}
