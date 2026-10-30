package com.zeekrprobe.journal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.zeekrprobe.MainActivity
import com.zeekrprobe.journal.ui.Bg
import com.zeekrprobe.journal.ui.Energy
import com.zeekrprobe.journal.ui.EnergyDim
import com.zeekrprobe.journal.ui.JournalTheme
import com.zeekrprobe.journal.ui.TextMid
import com.zeekrprobe.journal.ui.AddChargeDialog
import com.zeekrprobe.journal.ui.ChargesScreen
import com.zeekrprobe.journal.ui.DashboardScreen
import com.zeekrprobe.journal.ui.HistoryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class JournalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        CarRepo.init(this)
        requestNotifications()
        maybeStartService()
        setContent { JournalTheme { AppRoot() } }
    }

    private fun maybeStartService() {
        val enabled = getSharedPreferences("journal", MODE_PRIVATE).getBoolean("serviceEnabled", true)
        if (enabled) SamplingService.start(this)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

private enum class Tab(val title: String, val emoji: String) {
    DASHBOARD("Главная", "🏠"),
    CHARGES("Зарядки", "⚡"),
    HISTORY("История", "🛣"),
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val car by CarRepo.state.collectAsState()
    val charges by Store.charges.collectAsState()
    val stats = remember(charges) { Store.stats() }
    val pending = remember(charges) { charges.count { it.auto && !it.confirmed } }

    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChargeSession?>(null) }

    // Poll the car while this screen is on.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { CarRepo.refresh() }
            delay(1500)
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = com.zeekrprobe.journal.ui.Surface1) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.emoji, fontSize = 18.sp) },
                        label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Energy,
                            selectedTextColor = Energy,
                            indicatorColor = EnergyDim,
                            unselectedIconColor = TextMid,
                            unselectedTextColor = TextMid,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.HISTORY) {
                FloatingActionButton(
                    onClick = { editing = null; showAdd = true },
                    containerColor = Energy,
                ) { Text("+", fontSize = 28.sp, color = Bg) }
            }
        },
    ) { pad ->
        val inner = Modifier.padding(pad)
        when (tab) {
            Tab.DASHBOARD -> androidx.compose.foundation.layout.Box(inner) {
                DashboardScreen(
                    car = car,
                    stats = stats,
                    pending = pending,
                    onOpenProbe = { context.startActivity(Intent(context, MainActivity::class.java)) },
                )
            }
            Tab.CHARGES -> androidx.compose.foundation.layout.Box(inner) {
                ChargesScreen(charges = charges, onEdit = { editing = it; showAdd = true })
            }
            Tab.HISTORY -> androidx.compose.foundation.layout.Box(inner) {
                HistoryScreen(stats = stats)
            }
        }
    }

    if (showAdd) {
        AddChargeDialog(
            existing = editing,
            car = car,
            onDismiss = { showAdd = false; editing = null },
            onSaved = { showAdd = false; editing = null },
        )
    }
}
