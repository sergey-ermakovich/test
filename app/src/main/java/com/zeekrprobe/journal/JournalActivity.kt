package com.zeekrprobe.journal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.zeekrprobe.MainActivity
import com.zeekrprobe.Updater
import com.zeekrprobe.journal.ui.Bg
import com.zeekrprobe.journal.ui.Energy
import com.zeekrprobe.journal.ui.EnergyDim
import com.zeekrprobe.journal.ui.JournalTheme
import com.zeekrprobe.journal.ui.TextMid
import com.zeekrprobe.journal.ui.AddChargeDialog
import com.zeekrprobe.journal.ui.ChargesScreen
import com.zeekrprobe.journal.ui.DashboardScreen
import com.zeekrprobe.journal.ui.FilesScreen
import com.zeekrprobe.journal.ui.HistoryScreen
import com.zeekrprobe.journal.ui.RangeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JournalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        TripStore.init(this)
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
        val want = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) ActivityCompat.requestPermissions(this, want.toTypedArray(), 1)
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    DASHBOARD("Главная", Icons.Rounded.Home),
    CHARGES("Зарядки", Icons.Rounded.Bolt),
    HISTORY("История", Icons.Rounded.Route),
    RANGE("Запас", Icons.Rounded.Navigation),
    FILES("Файлы", Icons.Rounded.Folder),
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val car by CarRepo.state.collectAsState()
    val charges by Store.charges.collectAsState()
    val stats = remember(charges) { Store.stats() }
    val pending = remember(charges) { charges.count { it.auto && !it.confirmed } }
    val trip by TripRecorder.state.collectAsState()
    val tripSummary by TripRecorder.summary.collectAsState()
    val tripRecords by TripStore.trips.collectAsState()

    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChargeSession?>(null) }

    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<String?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    fun runUpdate() {
        if (updating) return
        updating = true
        updateProgress = "Соединяюсь…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    Updater(context) { line -> updateProgress = line }.updateNow(force = true)
                } catch (t: Throwable) {
                    "Не удалось обновить: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            updating = false
            updateProgress = null
            updateMsg = msg
        }
    }

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
                        icon = { Icon(t.icon, contentDescription = t.title) },
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
            if (tab == Tab.DASHBOARD || tab == Tab.CHARGES) {
                FloatingActionButton(
                    onClick = { editing = null; showAdd = true },
                    containerColor = Energy,
                ) { Icon(Icons.Rounded.Add, contentDescription = "Добавить", tint = Bg) }
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
                    trip = trip,
                    onStartTrip = { TripService.start(context) },
                    onStopTrip = { TripService.stop(context) },
                    updating = updating,
                    updateProgress = updateProgress,
                    onUpdate = { runUpdate() },
                    onOpenProbe = { context.startActivity(Intent(context, MainActivity::class.java)) },
                )
            }
            Tab.CHARGES -> androidx.compose.foundation.layout.Box(inner) {
                ChargesScreen(charges = charges, onEdit = { editing = it; showAdd = true })
            }
            Tab.HISTORY -> androidx.compose.foundation.layout.Box(inner) {
                HistoryScreen(stats = stats)
            }
            Tab.RANGE -> androidx.compose.foundation.layout.Box(inner) {
                RangeScreen(
                    car = car,
                    trips = tripRecords,
                    segmentConsumption = stats.consumptionPer100,
                )
            }
            Tab.FILES -> androidx.compose.foundation.layout.Box(inner) {
                FilesScreen()
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

    updateMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            confirmButton = {
                TextButton(onClick = { updateMsg = null }) { Text("Ок", color = Energy) }
            },
            title = { Text("Обновление") },
            text = { Text(msg) },
            containerColor = com.zeekrprobe.journal.ui.Surface1,
        )
    }

    tripSummary?.let { msg ->
        AlertDialog(
            onDismissRequest = { TripRecorder.clearSummary() },
            confirmButton = {
                TextButton(onClick = { TripRecorder.clearSummary() }) { Text("Ок", color = Energy) }
            },
            title = { Text("Поездка") },
            text = { Text(msg) },
            containerColor = com.zeekrprobe.journal.ui.Surface1,
        )
    }
}
