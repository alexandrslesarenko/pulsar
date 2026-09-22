package com.puls.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.time.YearMonth
import java.time.Month
import java.time.LocalDate
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.rememberUpdatedState
import android.view.MotionEvent
import android.widget.NumberPicker
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.puls.app.R
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.puls.app.ble.ConnState
import com.puls.app.ble.FoundDevice
import com.puls.app.ble.HrScanner
import com.puls.app.data.HealthSync
import com.puls.app.service.AlarmZone
import com.puls.app.service.HrService
import com.puls.app.service.HrVoice
import com.puls.app.data.HistoryTransfer
import com.puls.app.data.HrDb
import com.puls.app.service.HrZones
import com.puls.app.service.LiveHr
import com.puls.app.service.LiveState
import com.puls.app.service.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val AUTO_START_SCAN_MS = 15_000L
private const val ALARM_MIN = 40
private const val ALARM_MAX = 200
private val SLIDER_THUMB_INSET = 1.dp
private const val UNSET = "--"

class MainActivity : ComponentActivity() {
    private val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private lateinit var prefs: Prefs
    private var granted by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var promotedAllowed by mutableStateOf(true)
    private var hcGranted by mutableStateOf(false)
    private var hcEnabled by mutableStateOf(false)
    private var hcSyncedUntil by mutableStateOf(0L)
    private var autoSearching by mutableStateOf(false)
    private var released by mutableStateOf(false)
    /** Возраст из даты рождения; общий для карточки даты и карточки коридора. */
    private var age by mutableStateOf<Int?>(null)
    /** Палец на колесе даты: прокрутка страницы отключена, иначе она уводит жест у колеса. */
    private var wheelTouched by mutableStateOf(false)
    private var autoJob: Job? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
        autoStartIfNearby()
    }

    private val hcLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        if (HealthSync.PERMISSION in got) {
            prefs.hcEnabled = true
            refreshHealth()
            syncHealthNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        age = prefs.age
        enableEdgeToEdge()
        granted = hasPermissions()
        if (!granted) permLauncher.launch(permissions)
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }

    override fun onResume() {
        super.onResume()
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        released = prefs.released
        promotedAllowed = Build.VERSION.SDK_INT < 36 ||
            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        refreshHealth()
        autoStartIfNearby()
    }

    override fun onPause() {
        autoJob?.cancel()
        super.onPause()
    }

    /** Сбор остановлен, но датчик рядом - запускаем сами, без кнопки "Старт". */
    private fun autoStartIfNearby() {
        val address = prefs.deviceAddress ?: return
        if (prefs.released) return
        if (!granted || LiveHr.state.value.conn != ConnState.IDLE || autoJob?.isActive == true) return
        val scanner = HrScanner(this)
        if (!scanner.isBluetoothOn) return
        autoJob = lifecycleScope.launch {
            autoSearching = true
            try {
                if (scanner.isAdvertising(address, AUTO_START_SCAN_MS) && LiveHr.state.value.conn == ConnState.IDLE) {
                    HrService.start(this@MainActivity)
                }
            } finally {
                autoSearching = false
            }
        }
    }

    private fun refreshHealth() {
        lifecycleScope.launch {
            hcGranted = runCatching { HealthSync.hasPermission(this@MainActivity) }.getOrDefault(false)
            hcSyncedUntil = prefs.hcSyncedUntil
            hcEnabled = prefs.hcEnabled
        }
    }

    private fun syncHealthNow() {
        lifecycleScope.launch {
            runCatching { HealthSync.sync(this@MainActivity) }
            hcSyncedUntil = prefs.hcSyncedUntil
        }
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("InlinedApi")
    private fun openPromotionSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        var picking by remember { mutableStateOf(prefs.deviceAddress == null) }
        val pager = rememberPagerState { 3 }
        val scope = rememberCoroutineScope()

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!granted) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.perm_needed))
                    Button(onClick = { permLauncher.launch(permissions) }) { Text(stringResource(R.string.perm_grant)) }
                }
                return@Column
            }
            if (picking) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DevicePicker(
                        onPick = { d ->
                            prefs.deviceAddress = d.address
                            prefs.deviceName = d.name
                            picking = false
                            autoJob?.cancel()
                            HrService.start(this@MainActivity)
                        },
                        onCancel = if (prefs.deviceAddress != null) ({ picking = false }) else null,
                    )
                }
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                listOf(R.string.tab_now, R.string.tab_history, R.string.tab_settings).forEachIndexed { i, title ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text(stringResource(title)) },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    when (page) {
                        0 -> NowTab()
                        1 -> HistoryScreen()
                        else -> SettingsTab(onChangeDevice = {
                            autoJob?.cancel()
                            HrService.stop(this@MainActivity)
                            picking = true
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun NowTab() {
        val live by LiveHr.state.collectAsStateWithLifecycle()
        val recent by LiveHr.recent.collectAsStateWithLifecycle()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!batteryUnrestricted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.battery_hint))
                        Button(onClick = ::requestBatteryUnrestricted) { Text(stringResource(R.string.battery_unrestrict)) }
                    }
                }
            }
            if (live.alarm != AlarmZone.NORMAL) AlarmBanner(live)
            LivePanel(live)
            Text(
                stringResource(R.string.last_5_min),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HrChart(
                points = recent.map { (t, b) -> ChartPoint(t, b, b.toDouble(), b) },
                from = now - LiveHr.RECENT_WINDOW_MS, to = now, gapMs = 5_000,
                height = 180,
                corridor = if (prefs.alarmEnabled) prefs.alarmLow..prefs.alarmHigh else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live.conn == ConnState.IDLE) {
                    Button(onClick = {
                        autoJob?.cancel()
                        released = false
                        HrService.start(this@MainActivity)
                    }) { Text(stringResource(R.string.btn_start)) }
                } else {
                    Button(onClick = { HrService.stop(this@MainActivity) }) { Text(stringResource(R.string.btn_stop)) }
                }
                if (!released) {
                    OutlinedButton(onClick = {
                        autoJob?.cancel()
                        released = true
                        HrService.release(this@MainActivity)
                    }) { Text(stringResource(R.string.btn_release)) }
                }
            }
            if (released && live.conn == ConnState.IDLE) {
                Text(
                    stringResource(R.string.released_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (autoSearching && live.conn == ConnState.IDLE) {
                Text(
                    stringResource(R.string.auto_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun LivePanel(s: LiveState) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text(
                s.bpm?.takeIf { s.conn == ConnState.CONNECTED }?.toString() ?: "--",
                fontSize = 110.sp,
                fontWeight = FontWeight.Bold,
                color = when (s.alarm) {
                    AlarmZone.HIGH -> ZoneColors.High
                    AlarmZone.LOW -> ZoneColors.Low
                    AlarmZone.NORMAL -> MaterialTheme.colorScheme.primary
                },
            )
            Text(stringResource(R.string.bpm_unit), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(statusText(this@MainActivity, s))
            Text(
                deviceLine(this@MainActivity, s.deviceName ?: prefs.deviceName, s.battery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun AlarmBanner(s: LiveState) {
        val high = s.alarm == AlarmZone.HIGH
        val text = if (high) stringResource(R.string.alarm_above, prefs.alarmHigh) else stringResource(R.string.alarm_below, prefs.alarmLow)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                // Выше коридора - красный, ниже - жёлтый, как на графике и виджете.
                containerColor = if (high) ZoneColors.High else ZoneColors.Low,
                contentColor = if (high) Color.White else Color.Black,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text, style = MaterialTheme.typography.titleMedium)
                    if (s.alarmMuted) Text(stringResource(R.string.alarm_muted_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (!s.alarmMuted) {
                    Button(
                        onClick = { HrService.mute(this@MainActivity) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (high) Color.White else Color.Black,
                            contentColor = if (high) ZoneColors.High else ZoneColors.Low,
                        ),
                    ) { Text(stringResource(R.string.action_mute)) }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AlarmSettings() {
        var enabled by remember { mutableStateOf(prefs.alarmEnabled) }
        var lo by remember { mutableStateOf(prefs.alarmLow) }
        var hi by remember { mutableStateOf(prefs.alarmHigh) }
        fun save(newLo: Int, newHi: Int) {
            lo = newLo.coerceIn(ALARM_MIN, newHi - 1)
            hi = newHi.coerceIn(lo + 1, ALARM_MAX)
            prefs.alarmLow = lo
            prefs.alarmHigh = hi
        }
        SettingsCard(
            stringResource(R.string.alarm_title),
            stringResource(R.string.alarm_desc),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.alarmEnabled = it
                })
            }
            Text(stringResource(R.string.corridor, lo, hi), style = MaterialTheme.typography.titleMedium)
            RangeSlider(
                value = lo.toFloat()..hi.toFloat(),
                onValueChange = { r -> save(r.start.roundToInt(), r.endInclusive.roundToInt()) },
                valueRange = ALARM_MIN.toFloat()..ALARM_MAX.toFloat(),
                enabled = enabled,
            )
            val walk = age?.let { HrZones.walkZone(it) }
            RestNormScale(walk)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = lo == Prefs.REST_LOW && hi == Prefs.REST_HIGH,
                    enabled = enabled,
                    onClick = { save(Prefs.REST_LOW, Prefs.REST_HIGH) },
                    label = { Text(stringResource(R.string.preset_rest, Prefs.REST_LOW, Prefs.REST_HIGH)) },
                )
                FilterChip(
                    selected = walk != null && lo == walk.first && hi == walk.last,
                    enabled = enabled && walk != null,
                    onClick = { walk?.let { save(it.first, it.last) } },
                    label = {
                        Text(
                            if (walk != null) stringResource(R.string.preset_walk, walk.first, walk.last)
                            else stringResource(R.string.preset_walk_need_birth)
                        )
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.from), Modifier.width(28.dp))
                FilledTonalButton(enabled = enabled, onClick = { save(lo - 1, hi) }) { Text("-") }
                FilledTonalButton(enabled = enabled, onClick = { save(lo + 1, hi) }) { Text("+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.to), Modifier.width(28.dp))
                FilledTonalButton(enabled = enabled, onClick = { save(lo, hi - 1) }) { Text("-") }
                FilledTonalButton(enabled = enabled, onClick = { save(lo, hi + 1) }) { Text("+") }
            }
        }
    }

    /**
     * Шкала под ползунком: зелёная полоса - норма пульса в покое,
     * вторая полоса (если известен возраст) - зона прогулки.
     */
    @Composable
    private fun RestNormScale(walk: IntRange?) {
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val restCaption = stringResource(R.string.rest_norm)
        val walkCaption = stringResource(R.string.walk_zone)
        val walkColor = MaterialTheme.colorScheme.tertiary
        Canvas(Modifier.fillMaxWidth().height(if (walk != null) 68.dp else 34.dp)) {
            // Значения RangeSlider (Material 3) растянуты почти на всю ширину трека; отступ сверен замером.
            val inset = SLIDER_THUMB_INSET.toPx()
            val w = size.width - inset * 2
            fun x(v: Int) = inset + (v - ALARM_MIN).toFloat() / (ALARM_MAX - ALARM_MIN) * w
            fun band(range: IntRange, color: Color, caption: String, top: Float) {
                val x0 = x(range.first)
                val x1 = x(range.last)
                drawRoundRect(color.copy(alpha = 0.7f), Offset(x0, top), Size(x1 - x0, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                val y = top + 6.dp.toPx()
                for (v in listOf(range.first, range.last)) {
                    val tl = measurer.measure(v.toString(), labelStyle)
                    drawText(tl, topLeft = Offset((x(v) - tl.size.width / 2).coerceIn(0f, size.width - tl.size.width), y))
                }
                val tl = measurer.measure(caption, labelStyle)
                val cx = ((x0 + x1) / 2 - tl.size.width / 2).coerceIn(0f, size.width - tl.size.width)
                drawText(tl, topLeft = Offset(cx, y + tl.size.height))
            }
            band(Prefs.REST_LOW..Prefs.REST_HIGH, ZoneColors.In, restCaption, 0f)
            if (walk != null) band(walk, walkColor, walkCaption, 34.dp.toPx())
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HistorySettings() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val count by remember { HrDb.get(ctx).dao().count() }.collectAsState(0)
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        val exportDone = stringResource(R.string.history_exported)
        val importDone = stringResource(R.string.history_imported)
        val failed = stringResource(R.string.history_failed)

        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                result = runCatching { HistoryTransfer.export(ctx, uri) }
                    .fold({ exportDone.format(it) }, { failed.format(it.message ?: it.javaClass.simpleName) })
                busy = false
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                result = runCatching { HistoryTransfer.import(ctx, uri) }
                    .fold({ importDone.format(it.added, it.existing, it.skipped) }, { failed.format(it.message ?: it.javaClass.simpleName) })
                busy = false
            }
        }
        SettingsCard(stringResource(R.string.history_title), stringResource(R.string.history_desc, count)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && count > 0, onClick = {
                    val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    exportLauncher.launch("pulsar-history-$date.csv.gz")
                }) { Text(stringResource(R.string.history_export)) }
                OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.history_import))
                }
            }
            if (busy) Text(stringResource(R.string.history_busy), style = MaterialTheme.typography.bodySmall)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun BirthSettings() {
        var y by remember { mutableStateOf(prefs.birthYear) }
        var m by remember { mutableStateOf(prefs.birthMonth) }
        var d by remember { mutableStateOf(prefs.birthDay) }
        val maxYear = LocalDate.now().year - 5
        val years = remember(maxYear) { listOf(0) + (maxYear downTo 1920) }
        val locale = LocalConfiguration.current.locales[0]
        val months = remember(locale) {
            listOf(UNSET) + Month.entries.map { it.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, locale) }
        }
        val dayCount = if (y > 0 && m > 0) YearMonth.of(y, m).lengthOfMonth() else 31

        fun save(ny: Int, nm: Int, nd: Int) {
            y = ny
            m = if (ny == 0) 0 else nm
            d = if (m == 0) 0 else nd.coerceAtMost(YearMonth.of(y, m).lengthOfMonth())
            prefs.birthYear = y
            prefs.birthMonth = m
            prefs.birthDay = d
            age = prefs.age
        }

        val a = age
        SettingsCard(
            stringResource(R.string.birth_title),
            if (a != null) stringResource(R.string.birth_age, a, HrZones.maxHr(a)) else stringResource(R.string.birth_hint),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Wheel(
                    stringResource(R.string.birth_year),
                    years.map { if (it == 0) UNSET else it.toString() },
                    if (y == 0) 0 else years.indexOf(y).coerceAtLeast(0),
                    enabled = true,
                    onChange = { save(years[it], m, d) },
                    modifier = Modifier.weight(1.2f),
                )
                Wheel(
                    stringResource(R.string.birth_month),
                    months,
                    m,
                    enabled = y > 0,
                    onChange = { save(y, it, d) },
                    modifier = Modifier.weight(1f),
                )
                Wheel(
                    stringResource(R.string.birth_day),
                    listOf(UNSET) + (1..dayCount).map { it.toString() },
                    d,
                    enabled = m > 0,
                    onChange = { save(y, m, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    /** Колесо выбора (системный NumberPicker); values[index] - текущее значение. */
    @Composable
    private fun Wheel(
        label: String,
        values: List<String>,
        index: Int,
        enabled: Boolean,
        onChange: (Int) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val onChangeState = rememberUpdatedState(onChange)
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AndroidView(
                factory = { ctx ->
                    NumberPicker(ctx).apply {
                        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                        setOnValueChangedListener { _, _, v -> onChangeState.value(v) }
                        setOnTouchListener { _, e ->
                            when (e.actionMasked) {
                                MotionEvent.ACTION_DOWN -> wheelTouched = true
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> wheelTouched = false
                            }
                            false
                        }
                    }
                },
                update = { p ->
                    if (p.displayedValues?.toList() != values) {
                        // Сначала снять старые подписи: иначе смена maxValue упадёт на несовпадении длины.
                        p.displayedValues = null
                        p.minValue = 0
                        p.maxValue = values.size - 1
                        p.displayedValues = values.toTypedArray()
                        p.wrapSelectorWheel = false
                    }
                    p.value = index.coerceIn(0, values.size - 1)
                    p.isEnabled = enabled
                    p.alpha = if (enabled) 1f else 0.4f
                    p.textColor = textColor
                },
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun VoiceSettings() {
        val ctx = LocalContext.current
        val voice = remember { HrVoice(ctx, prefs) }
        DisposableEffect(Unit) { onDispose { voice.shutdown() } }
        var enabled by remember { mutableStateOf(prefs.voiceEnabled) }
        var interval by remember { mutableStateOf(prefs.voiceIntervalMin) }
        val headphones = remember { voice.headphonesConnected() }
        SettingsCard(
            stringResource(R.string.voice_title),
            stringResource(R.string.voice_desc) + " " + stringResource(if (headphones) R.string.headphones_on else R.string.headphones_off),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.voiceEnabled = it
                })
            }
            Text(stringResource(R.string.voice_every), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 5).forEach { v ->
                    val label = if (v == 0) stringResource(R.string.voice_on_change) else stringResource(R.string.minutes_n, v)
                    FilterChip(
                        selected = interval == v,
                        enabled = enabled,
                        onClick = {
                            interval = v
                            prefs.voiceIntervalMin = v
                        },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedButton(onClick = { voice.test(LiveHr.state.value.bpm) }) { Text(stringResource(R.string.voice_test_btn)) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsTab(onChangeDevice: () -> Unit) {
        val timeFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = !wheelTouched),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(stringResource(R.string.sensor_title), prefs.deviceName ?: prefs.deviceAddress ?: stringResource(R.string.sensor_none)) {
                OutlinedButton(onClick = onChangeDevice) { Text(stringResource(R.string.change_sensor)) }
            }

            HistorySettings()
            BirthSettings()
            AlarmSettings()
            VoiceSettings()

            val hcText = when {
                !HealthSync.isAvailable(this@MainActivity) -> stringResource(R.string.hc_unavailable)
                hcEnabled && hcGranted -> stringResource(R.string.hc_on) + " " +
                    if (hcSyncedUntil > 0) stringResource(R.string.hc_synced_until, timeFmt.format(Date(hcSyncedUntil)))
                    else stringResource(R.string.hc_nothing_sent)
                else -> stringResource(R.string.hc_offer)
            }
            SettingsCard("Health Connect", hcText) {
                if (HealthSync.isAvailable(this@MainActivity)) {
                    if (hcEnabled && hcGranted) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::syncHealthNow) { Text(stringResource(R.string.hc_send_now)) }
                            OutlinedButton(onClick = {
                                prefs.hcEnabled = false
                                refreshHealth()
                            }) { Text(stringResource(R.string.hc_disable)) }
                        }
                    } else {
                        Button(onClick = {
                            if (hcGranted) {
                                prefs.hcEnabled = true
                                refreshHealth()
                                syncHealthNow()
                            } else {
                                hcLauncher.launch(setOf(HealthSync.PERMISSION))
                            }
                        }) { Text(stringResource(R.string.hc_connect)) }
                    }
                }
            }

            NotificationSettings()

            if (Build.VERSION.SDK_INT >= 36) {
                SettingsCard(
                    stringResource(R.string.nowbar_title),
                    stringResource(if (promotedAllowed) R.string.nowbar_on else R.string.nowbar_off),
                ) {
                    if (!promotedAllowed) Button(onClick = ::openPromotionSettings) { Text(stringResource(R.string.enable)) }
                }
            }

            SettingsCard(
                stringResource(R.string.bg_title),
                stringResource(if (batteryUnrestricted) R.string.bg_ok else R.string.bg_bad),
            ) {
                if (!batteryUnrestricted) Button(onClick = ::requestBatteryUnrestricted) { Text(stringResource(R.string.battery_unrestrict)) }
            }
        }
    }

    @Composable
    private fun NotificationSettings() {
        var show by remember { mutableStateOf(prefs.showInNotification) }
        SettingsCard(
            stringResource(R.string.notif_setting_title),
            stringResource(if (show) R.string.notif_setting_on else R.string.notif_setting_off),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(checked = show, onCheckedChange = {
                    show = it
                    prefs.showInNotification = it
                    HrService.refresh(this@MainActivity)
                })
            }
        }
    }

    @Composable
    private fun SettingsCard(title: String, text: String, actions: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                actions()
            }
        }
    }

    @Composable
    private fun DevicePicker(onPick: (FoundDevice) -> Unit, onCancel: (() -> Unit)?) {
        val ctx = LocalContext.current
        val scanner = remember { HrScanner(ctx) }
        val found = remember { mutableStateMapOf<String, FoundDevice>() }
        var showAll by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            var job: Job? = null
            if (scanner.isBluetoothOn) {
                job = lifecycleScope.launch { scanner.scan().collect { found.putAll(it) } }
            }
            onDispose { job?.cancel() }
        }

        Text(stringResource(R.string.pick_title), style = MaterialTheme.typography.titleLarge)
        if (!scanner.isBluetoothOn) {
            Text(stringResource(R.string.bt_off))
        } else {
            Text(
                stringResource(R.string.pick_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        onCancel?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.cancel)) } }
        val others = found.values.count { !it.isHrSensor }
        val list = found.values
            .filter { showAll || it.isHrSensor }
            .sortedWith(compareByDescending<FoundDevice> { it.isHrSensor }.thenByDescending { it.rssi })
        if (others > 0) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) stringResource(R.string.show_hr_only) else stringResource(R.string.show_all_devices, others))
            }
        }
        val sensorPrefix = stringResource(R.string.hr_sensor_prefix)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.address }) { d ->
                Card(Modifier.fillMaxWidth().clickable { onPick(d) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name ?: d.address, fontWeight = if (d.isHrSensor) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            (if (d.isHrSensor) sensorPrefix else "") + "${d.rssi} dBm - ${d.address}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
