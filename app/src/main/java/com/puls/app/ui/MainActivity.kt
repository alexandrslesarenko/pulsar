package com.puls.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager
import android.os.Bundle
import java.time.YearMonth
import java.time.Month
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalTextStyle
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.luminance
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.hardware.Sensor
import android.hardware.SensorManager
import com.puls.app.ble.ConnState
import com.puls.app.ble.FoundDevice
import com.puls.app.ble.HrScanner
import com.puls.app.ble.ScanFailed
import com.puls.app.data.HealthSync
import com.puls.app.service.AlarmZone
import com.puls.app.service.HrService
import com.puls.app.service.HrVoice
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.puls.app.service.VoiceLang
import android.speech.tts.TextToSpeech
import com.puls.app.data.HistoryTransfer
import com.puls.app.data.HrDb
import com.puls.app.service.HrZones
import com.puls.app.service.LiveHr
import com.puls.app.service.LiveState
import com.puls.app.service.Prefs
import com.puls.app.service.Profile
import com.puls.app.data.ProfileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val AUTO_START_SCAN_MS = 15_000L
private const val STATE_SETTINGS_PAGE = "settings_page"
private const val LANGUAGE_EN = "Language"
private const val ALARM_MIN = 40
private const val ALARM_MAX = 200
private val SLIDER_THUMB_INSET = 1.dp
private const val UNSET = "--"
private const val NIGHT_STEP_MIN = 30

/** Не null - карточки настроек показываются отдельной страницей с кнопкой назад. */
private val LocalSettingsBack = compositionLocalOf<(() -> Unit)?> { null }

/** Пункты настроек в порядке плиток: откуда данные -> как реагировать -> где показывать -> куда сохранять. */
private enum class SettingsPage(@StringRes val title: Int, @DrawableRes val icon: Int, val color: Color) {
    SENSOR(R.string.sensor_title, R.drawable.ic_set_bluetooth, Color(0xFF3F8FE8)),
    ABOUT(R.string.about_title, R.drawable.ic_set_person, Color(0xFF26A69A)),
    SPEED(R.string.speed_title, R.drawable.ic_set_directions_run, Color(0xFF3BA55C)),
    ALARM(R.string.tile_alarm_title, R.drawable.ic_set_monitor_heart, Color(0xFFE5484D)),
    NIGHT(R.string.tile_night_title, R.drawable.ic_set_bedtime, Color(0xFF8E6BE0)),
    VOICE(R.string.tile_voice_title, R.drawable.ic_set_headphones, Color(0xFFE8912D)),
    NOTIFICATION(R.string.tile_notif_title, R.drawable.ic_set_notifications, Color(0xFFD4A20F)),
    THEME(R.string.theme_title, R.drawable.ic_set_palette, Color(0xFF00A5B8)),
    LANGUAGE(R.string.lang_title, R.drawable.ic_set_language, Color(0xFF5C6BC0)),
    HISTORY(R.string.history_title, R.drawable.ic_set_history, Color(0xFF7D8FA3)),
    HEALTH(R.string.tile_hc_title, R.drawable.ic_set_favorite, Color(0xFFE0457B)),
}
private val ZONE_BADGE_COLUMN = 48.dp
/**
 * Языки приложения: тег и самоназвание. Самоназвание не переводится - свой язык
 * должно быть легко найти, в каком бы языке ни открылось приложение.
 */
private val APP_LANGUAGES = listOf(
    "en" to "English", "ru" to "Русский", "de" to "Deutsch", "fr" to "Français",
    "es" to "Español", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "zh-CN" to "简体中文",
)
/** Цвета зон 1-5 по привычной шкале спортивных часов: от спокойного к максимальному. */
private val ZONE_COLORS = listOf(
    Color(0xFF8E9AA6), Color(0xFF3F8FE8), Color(0xFF3BA55C), Color(0xFFE8912D), Color(0xFFE5484D),
)
/** Сколько мс устройство остаётся в списке выбора после последнего пакета рекламы. */
private const val FOUND_STALE_MS = 20_000L
private const val SLEEP_LOW_MIN = 30
private const val SLEEP_LOW_MAX = 59
private const val RESCAN_PAUSE_MS = 10_000L
private const val HEIGHT_MIN = 120
private const val HEIGHT_MAX = 220
private const val SEARCH_MIN_MIN = 1
private const val SEARCH_MAX_MIN = 10

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
    private var hcBusy by mutableStateOf(false)
    private var hcResult by mutableStateOf<String?>(null)
    private var autoSearching by mutableStateOf(false)
    private var released by mutableStateOf(false)
    /** Возраст из даты рождения; общий для карточки даты и карточки коридора. */
    private var age by mutableStateOf<Int?>(null)
    private var profile by mutableStateOf(Profile.REST)
    private var autoProfile by mutableStateOf(false)
    /** Копия prefs.stepsEnabled для экрана: от неё зависит, доступно ли "Авто". */
    private var stepsEnabled by mutableStateOf(false)
    private var themeMode by mutableStateOf(Prefs.THEME_SYSTEM)
    /** Язык приложения, выбранный вручную (тег); "" - как в системе. */
    private var appLanguage by mutableStateOf("")
    /** Открытый пункт настроек; null - плитки. */
    private var settingsPage by mutableStateOf<SettingsPage?>(null)
    /** Коридор активного профиля для живого графика; раскрашивается и при выключенном сигнале. */
    private var corridor by mutableStateOf<IntRange?>(null)
    /** Палец на колесе даты: прокрутка страницы отключена, иначе она уводит жест у колеса. */
    private var wheelTouched by mutableStateOf(false)
    private var autoJob: Job? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
        autoStartIfNearby()
    }

    private var stepsGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)

    private val stepsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        stepsGranted = ok
        prefs.stepsEnabled = ok
        stepsEnabled = ok
        HrService.refresh(this)
    }

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = has(Manifest.permission.ACCESS_FINE_LOCATION)
        prefs.gpsInTraining = locationGranted
        HrService.refresh(this)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val hcLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        if (HealthSync.PERMISSION in got) {
            prefs.hcEnabled = true
            refreshHealth()
            syncHealthNow()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        settingsPage?.let { outState.putString(STATE_SETTINGS_PAGE, it.name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        age = prefs.age
        profile = prefs.profile
        autoProfile = prefs.autoProfile
        stepsEnabled = prefs.stepsEnabled
        corridorChanged()
        // Автовыбор меняет профиль в сервисе; экран узнаёт об этом из LiveHr.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Только смены профиля: на каждом замере старое значение из сервиса
                // откатило бы только что нажатый вручную профиль.
                LiveHr.state.map { it.profile }.distinctUntilChanged().collect { p ->
                    if (p != null && p != profile) {
                        profile = p
                        corridor = prefs.range(p)
                    }
                }
            }
        }
        themeMode = prefs.theme
        appLanguage = currentAppLanguage()
        // Смена языка пересоздаёт экран: остаёмся на той же странице настроек.
        settingsPage = savedInstanceState?.getString(STATE_SETTINGS_PAGE)?.let { n -> SettingsPage.entries.firstOrNull { it.name == n } }
        granted = hasPermissions()
        if (!granted) permLauncher.launch(permissions)
        setContent {
            val dark = when (themeMode) {
                Prefs.THEME_LIGHT -> false
                Prefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // Значки строки состояния - по теме приложения, а не системы: иначе светлая тема
            // в тёмной системе получит белые значки на белом.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(dark) { Surface(Modifier.fillMaxSize()) { Root() } }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        released = prefs.released
        stepsGranted = has(Manifest.permission.ACTIVITY_RECOGNITION)
        profile = prefs.profile
        corridor = prefs.range(profile)
        locationGranted = has(Manifest.permission.ACCESS_FINE_LOCATION)
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

    /** Отправка по кнопке: показываем, что идёт и чем кончилось, иначе нажатие выглядит впустую. */
    private fun syncHealthNow() {
        if (hcBusy) return
        hcBusy = true
        hcResult = null
        lifecycleScope.launch {
            hcResult = runCatching { HealthSync.sync(this@MainActivity) }.fold(
                { n -> if (n > 0) getString(R.string.hc_sent_n, n) else getString(R.string.hc_sent_none) },
                { e -> getString(R.string.history_failed, e.message ?: e.javaClass.simpleName) },
            )
            hcSyncedUntil = prefs.hcSyncedUntil
            hcBusy = false
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
                        // При крупном системном шрифте подпись уменьшается, а не рвётся на две строки.
                        text = { OneLineText(stringResource(title)) },
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

        // График последних минут растягивается до низа экрана, чтобы экран не пустовал.
        Column(
            Modifier.fillMaxSize(),
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
            MainProfileChips()
            if (!stepsAvailable()) {
                Text(
                    stringResource(R.string.profile_auto_no_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.last_5_min),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HrChart(
                points = recent.map { (t, b) -> ChartPoint(t, b, b.toDouble(), b) },
                from = now - LiveHr.RECENT_WINDOW_MS, to = now, gapMs = 5_000,
                height = null,
                modifier = Modifier.weight(1f),
                spans = listOf(ChartSpan(now - LiveHr.RECENT_WINDOW_MS, now + 1, corridor)),
            )
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
            s.speedKmh?.let { Text(stringResource(R.string.speed_now, it), style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp))
            Text(statusText(this@MainActivity, s))
            Text(
                deviceLine(this@MainActivity, s.deviceName ?: prefs.deviceName, s.battery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Остановить сбор можно в уведомлении или в настройках датчика: на главном экране
            // кнопку легко задеть, а дыра в истории останется. Старт нужен, только когда сбор стоит.
            if (s.conn == ConnState.IDLE) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = ::startCollecting) { Text(stringResource(R.string.btn_start)) }
            }
        }
    }

    private fun startCollecting() {
        autoJob?.cancel()
        released = false
        HrService.start(this)
    }

    @Composable
    private fun AlarmBanner(s: LiveState) {
        val high = s.alarm == AlarmZone.HIGH
        val b = s.bounds ?: prefs.range(profile)
        val text = if (high) stringResource(R.string.alarm_above, b.last) else stringResource(R.string.alarm_below, b.first)
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
                if (!s.alarmMuted && s.alarmVibrates) {
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
    /**
     * Кнопки профилей; размер подписей подбирает FitRow, чтобы все три встали в строку.
     * auto - профиль выбран автоматически: подпись выбранного чипа цветом профиля.
     * Цвет, а не значок: значок расширяет чип, и ряд уходит на шрифт мельче.
     */
    private fun ProfileChips(selected: Profile, auto: Boolean = false, onSelect: (Profile) -> Unit) {
        FitRow { style ->
            Profile.entries.forEach { p ->
                FilterChip(
                    selected = selected == p,
                    onClick = { onSelect(p) },
                    label = {
                        Text(
                            stringResource(p.label), maxLines = 1, style = style,
                            color = if (auto && selected == p) {
                                profileTextColor(p, dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * Главный экран: чипы показывают активный профиль, переключатель под ними - автовыбор.
     * "Авто" отдельным переключателем, а не четвёртым чипом: четыре чипа при крупном
     * шрифте в строку не встают, да и это режим, а не профиль.
     */
    @Composable
    private fun MainProfileChips() {
        val available = stepsAvailable()
        val auto = autoProfile && available
        ProfileChips(profile, auto = auto, onSelect = ::activate)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (auto) R.string.profile_auto_on else R.string.profile_auto_off),
                Modifier.weight(1f),
                color = if (available) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = auto, enabled = available, onCheckedChange = { if (it) enableAuto() else disableAuto() })
        }
    }

    /** Автовыбору нужен шагомер: без шагов прогулку от покоя не отличить. */
    private fun stepsAvailable(): Boolean = stepsEnabled && stepsGranted &&
        getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    private fun enableAuto() {
        if (autoProfile) return
        prefs.autoProfile = true
        autoProfile = true
        HrService.refresh(this)
    }

    /** Выключили автовыбор: остаётся профиль, который он выбрал последним. */
    private fun disableAuto() {
        activate(profile)
    }

    /**
     * Активация профиля - только с главного экрана; в настройках профиль лишь редактируется.
     * Ручной выбор выключает автовыбор: взял управление - значит, сам.
     */
    private fun activate(p: Profile) {
        prefs.autoProfile = false
        autoProfile = false
        prefs.profile = p
        profile = p
        corridorChanged()
    }

    /** Коридор или профиль изменились: обновить экран, уведомление и журнал для истории. */
    private fun corridorChanged(log: Boolean = true) {
        corridor = prefs.range(prefs.profile)
        HrService.refresh(this)
        if (log) lifecycleScope.launch(Dispatchers.IO) { runCatching { ProfileLog.record(this@MainActivity) } }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AlarmSettings() {
        // Какой профиль редактируем; открываем активный, но выбор здесь его не активирует.
        var p by remember { mutableStateOf(profile) }
        var enabled by remember(p) { mutableStateOf(prefs.alarmEnabled(p)) }
        var lo by remember(p, age) { mutableStateOf(prefs.range(p).first) }
        var hi by remember(p, age) { mutableStateOf(prefs.range(p).last) }
        var auto by remember { mutableStateOf(prefs.walkAuto) }
        var vibrate by remember(p) { mutableStateOf(prefs.vibrate(p)) }
        val a = age
        val locked = p == Profile.WALK && auto && a != null
        // Ползунок и кнопки легко задеть при прокрутке, поэтому коридор правится только после "Изменить".
        var editing by remember(p) { mutableStateOf(false) }
        var isDefault by remember(p, age) { mutableStateOf(prefs.isDefaultRange(p)) }
        fun save(newLo: Int, newHi: Int, log: Boolean) {
            lo = newLo.coerceIn(ALARM_MIN, newHi - 1)
            hi = newHi.coerceIn(lo + 1, ALARM_MAX)
            prefs.setRange(p, lo, hi)
            isDefault = prefs.isDefaultRange(p)
            corridorChanged(log)
        }
        SettingsCard(
            stringResource(R.string.alarm_title),
            help = stringResource(R.string.alarm_desc) + "\n\n" + stringResource(R.string.profile_hint) +
                "\n\n" + stringResource(R.string.profile_auto_hint),
            helpExtra = { ZonesTable(age) },
        ) {
            ProfileChips(p) { p = it }
            if (p != profile) {
                Text(
                    stringResource(R.string.profile_active_note, stringResource(profile.label)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (enabled) R.string.alarm_on else R.string.alarm_off), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.setAlarmEnabled(p, it)
                    corridorChanged()
                })
            }
            Text(stringResource(R.string.corridor, lo, hi), style = MaterialTheme.typography.titleMedium)
            RangeSlider(
                value = lo.toFloat()..hi.toFloat(),
                onValueChange = { r -> save(r.start.roundToInt(), r.endInclusive.roundToInt(), log = false) },
                // Ползунок меняет значение много раз в секунду; в журнал пишем, когда палец отпущен.
                onValueChangeFinished = { corridorChanged() },
                valueRange = ALARM_MIN.toFloat()..ALARM_MAX.toFloat(),
                // Коридор нужен и для раскраски истории, поэтому правится и при выключенном сигнале.
                enabled = editing && !locked,
            )
            ReferenceScale(p, a)
            if (editing && !locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.from), Modifier.width(28.dp))
                    StepButtons(true, { save(lo - 1, hi, log = true) }, { save(lo + 1, hi, log = true) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.to), Modifier.width(28.dp))
                    StepButtons(true, { save(lo, hi - 1, log = true) }, { save(lo, hi + 1, log = true) })
                }
            }
            FitRow { style ->
                OutlinedButton(onClick = { editing = !editing }) {
                    Text(stringResource(if (editing) R.string.birth_done else R.string.birth_edit), maxLines = 1, style = style)
                }
                OutlinedButton(enabled = !isDefault, onClick = {
                    prefs.resetRange(p)
                    auto = prefs.walkAuto
                    lo = prefs.range(p).first
                    hi = prefs.range(p).last
                    isDefault = prefs.isDefaultRange(p)
                    editing = false
                    corridorChanged()
                }) { Text(stringResource(R.string.corridor_default), maxLines = 1, style = style) }
            }
            // Переключатель расчёта по возрасту - тоже часть коридора, поэтому только в режиме правки.
            if (p == Profile.WALK && (editing || a == null)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.walk_auto), Modifier.weight(1f))
                    Switch(checked = auto && a != null, enabled = a != null, onCheckedChange = {
                        auto = it
                        prefs.walkAuto = it
                        // Выключили авто - начинаем ручную правку с расчётного коридора.
                        if (!it && a != null) HrZones.walkZone(a).let { w -> prefs.setRange(p, w.first, w.last) }
                        lo = prefs.range(p).first
                        hi = prefs.range(p).last
                        isDefault = prefs.isDefaultRange(p)
                        corridorChanged()
                    })
                }
                if (a == null) {
                    Text(
                        stringResource(R.string.walk_auto_need_birth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (p == Profile.TRAINING) {
                var gps by remember { mutableStateOf(prefs.gpsInTraining) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gps_training), Modifier.weight(1f))
                    Switch(checked = gps && locationGranted, onCheckedChange = {
                        gps = it
                        if (it && !locationGranted) {
                            locationLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        } else {
                            prefs.gpsInTraining = it
                            HrService.refresh(this@MainActivity)
                        }
                    })
                }
                Text(
                    stringResource(R.string.gps_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vibrate_label), Modifier.weight(1f))
                Switch(checked = vibrate, enabled = enabled, onCheckedChange = {
                    vibrate = it
                    prefs.setVibrate(p, it)
                    HrService.refresh(this@MainActivity)
                })
            }
        }
    }

    @Composable
    private fun SpeedSettings() {
        var on by remember { mutableStateOf(prefs.stepsEnabled) }
        SettingsCard(stringResource(R.string.speed_title), help = stringResource(R.string.speed_desc)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Переключатель - только шагомер: GPS в тренировке включается в карточке сигнала.
                Text(stringResource(if (on && stepsGranted) R.string.steps_on else R.string.steps_off), Modifier.weight(1f))
                Switch(checked = on && stepsGranted, onCheckedChange = {
                    on = it
                    if (it && !stepsGranted) {
                        stepsLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        prefs.stepsEnabled = it
                        stepsEnabled = it
                        HrService.refresh(this@MainActivity)
                    }
                })
            }
            if (on && stepsGranted && prefs.heightCm <= 0) {
                Text(
                    stringResource(R.string.speed_need_height),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun NightSettings() {
        var on by remember { mutableStateOf(prefs.nightQuiet) }
        var from by remember { mutableStateOf(prefs.nightFrom) }
        var to by remember { mutableStateOf(prefs.nightTo) }
        fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
        fun shift(min: Int, delta: Int) = Math.floorMod(min + delta, 24 * 60)
        var sleepLow by remember { mutableStateOf(prefs.sleepLow) }
        fun setSleepLow(v: Int) {
            sleepLow = v.coerceIn(SLEEP_LOW_MIN, SLEEP_LOW_MAX)
            prefs.sleepLow = sleepLow
            HrService.refresh(this@MainActivity)
        }
        // Часы ночи общие: по ним и тишина вибрации, и граница сна, поэтому правятся всегда.
        SettingsCard(stringResource(R.string.tile_night_title), help = stringResource(R.string.night_desc)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (on) R.string.night_quiet_on else R.string.night_quiet_off), Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = {
                    on = it
                    prefs.nightQuiet = it
                })
            }
            for (isFrom in listOf(true, false)) {
                val v = if (isFrom) from else to
                fun set(nv: Int) {
                    if (isFrom) { from = nv; prefs.nightFrom = nv } else { to = nv; prefs.nightTo = nv }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (isFrom) R.string.night_from else R.string.night_to, hm(v)),
                        Modifier.width(88.dp),
                    )
                    StepButtons(true, { set(shift(v, -NIGHT_STEP_MIN)) }, { set(shift(v, NIGHT_STEP_MIN)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sleep_low, sleepLow), Modifier.weight(1f))
                StepButtons(true, { setSleepLow(sleepLow - 1) }, { setSleepLow(sleepLow + 1) })
            }
        }
    }

    /**
     * Справочная полоса под ползунком для выбранного профиля: норма в покое, зона прогулки
     * или зона тренировки по возрасту. Без даты рождения у прогулки и тренировки её нет.
     */
    @Composable
    private fun ReferenceScale(p: Profile, age: Int?) {
        val range = when (p) {
            Profile.REST -> Prefs.REST_LOW..Prefs.REST_HIGH
            Profile.WALK -> age?.let { HrZones.walkZone(it) }
            Profile.TRAINING -> age?.let { HrZones.trainingZone(it) }
        } ?: return
        val caption = stringResource(
            when (p) {
                Profile.REST -> R.string.rest_norm
                Profile.WALK -> R.string.walk_zone
                Profile.TRAINING -> R.string.training_zone
            }
        )
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val color = profileColor(p)
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            // Значения RangeSlider (Material 3) растянуты почти на всю ширину трека; отступ сверен замером.
            val inset = SLIDER_THUMB_INSET.toPx()
            val w = size.width - inset * 2
            fun x(v: Int) = inset + (v - ALARM_MIN).toFloat() / (ALARM_MAX - ALARM_MIN) * w
            val x0 = x(range.first)
            val x1 = x(range.last)
            drawRoundRect(color, Offset(x0, 0f), Size(x1 - x0, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            val y = 6.dp.toPx()
            for (v in listOf(range.first, range.last)) {
                val tl = measurer.measure(v.toString(), labelStyle)
                drawText(tl, topLeft = Offset((x(v) - tl.size.width / 2).coerceIn(0f, size.width - tl.size.width), y))
            }
            val tl = measurer.measure(caption, labelStyle)
            val cx = ((x0 + x1) / 2 - tl.size.width / 2).coerceIn(0f, size.width - tl.size.width)
            drawText(tl, topLeft = Offset(cx, y + tl.size.height))
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
        SettingsCard(
            stringResource(R.string.history_title),
            stringResource(R.string.history_count, count),
            help = stringResource(R.string.history_help),
        ) {
            FitRow { style ->
                OutlinedButton(enabled = !busy && count > 0, onClick = {
                    val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    exportLauncher.launch("pulsar-history-$date.csv.gz")
                }) { Text(stringResource(R.string.history_export), maxLines = 1, style = style) }
                OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.history_import), maxLines = 1, style = style)
                }
            }
            if (busy) Text(stringResource(R.string.history_busy), style = MaterialTheme.typography.bodySmall)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun AboutSettings() {
        var height by remember { mutableStateOf(prefs.heightCm) }
        val heights = remember { listOf(0) + (HEIGHT_MIN..HEIGHT_MAX) }
        fun setHeight(v: Int) {
            height = v
            // Сервис читает рост при каждом расчёте скорости, перезапускать его не нужно.
            prefs.heightCm = v
        }
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
            // От возраста зависит коридор прогулки в режиме "авто".
            corridorChanged()
        }

        val a = age
        // Колёса и кнопки легко задеть при прокрутке настроек, поэтому по умолчанию данные только показываются.
        var editing by remember { mutableStateOf(false) }
        SettingsCard(stringResource(R.string.about_title), help = stringResource(R.string.about_desc)) {
            if (!editing) {
                AboutLine(stringResource(R.string.birth_title), birthText(y, m, d, locale) ?: stringResource(R.string.birth_unset))
                AboutLine(
                    stringResource(R.string.height_title),
                    if (height > 0) stringResource(R.string.height_value, height) else stringResource(R.string.height_unset),
                )
                if (a != null) {
                    Text(
                        stringResource(R.string.about_age, a, HrZones.maxHr(a)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.birth_edit)) }
                return@SettingsCard
            }
            Text(stringResource(R.string.birth_title), style = MaterialTheme.typography.titleSmall)
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
            Text(stringResource(R.string.height_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Wheel(
                    stringResource(R.string.height_wheel),
                    heights.map { if (it == 0) UNSET else it.toString() },
                    heights.indexOf(height).coerceAtLeast(0),
                    enabled = true,
                    onChange = { setHeight(heights[it]) },
                    modifier = Modifier.weight(1.2f),
                )
                // Ширина колеса - как у года рождения.
                Spacer(Modifier.weight(2f))
            }
            OutlinedButton(onClick = { editing = false }) { Text(stringResource(R.string.birth_done)) }
        }
    }

    /**
     * Таблица зон пульса для пояснения в карточке сигнала: номер в цветном кружке,
     * доля от максимума, пульс для возраста (если известен), под ними - что это за нагрузка.
     */
    @Composable
    private fun ZonesTable(age: Int?) {
        val descs = listOf(R.string.zone1_desc, R.string.zone2_desc, R.string.zone3_desc, R.string.zone4_desc, R.string.zone5_desc)
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (age != null) stringResource(R.string.zones_title_age, HrZones.maxHr(age)) else stringResource(R.string.zones_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.outlineVariant, shape)) {
                Row(Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val head = MaterialTheme.typography.labelMedium
                    Text(stringResource(R.string.zones_col_zone), Modifier.width(ZONE_BADGE_COLUMN), style = head, color = colors.onSurfaceVariant)
                    Text(stringResource(R.string.zones_col_pct), Modifier.weight(1f), style = head, color = colors.onSurfaceVariant)
                    if (age != null) Text(stringResource(R.string.zones_col_bpm), Modifier.weight(1f), style = head, color = colors.onSurfaceVariant)
                }
                HrZones.ZONE_PCT.forEachIndexed { i, (lo, hi) ->
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(8.dp)) {
                        Box(Modifier.width(ZONE_BADGE_COLUMN)) {
                            Box(
                                Modifier.size(24.dp).background(ZONE_COLORS[i], CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(stringResource(R.string.zone_pct, lo, hi), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                age?.let { HrZones.pctRange(it, lo, hi) }?.let {
                                    Text(stringResource(R.string.zone_bpm, it.first, it.last), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Text(stringResource(descs[i]), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.zones_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }

    /** Пара кнопок "меньше / больше" в стиле остальных кнопок настроек: контурные, со стрелками. */
    @Composable
    private fun StepButtons(enabled: Boolean, onDec: () -> Unit, onInc: () -> Unit) {
        OutlinedButton(enabled = enabled, onClick = onDec) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.step_down))
        }
        OutlinedButton(enabled = enabled, onClick = onInc) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.step_up))
        }
    }

    @Composable
    private fun AboutLine(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }

    /** Дата рождения текстом: "15 марта 1975", "март 1975" или "1975"; null - не указана. */
    private fun birthText(y: Int, m: Int, d: Int, locale: Locale): String? = when {
        y == 0 -> null
        m == 0 -> y.toString()
        d == 0 -> YearMonth.of(y, m).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        else -> LocalDate.of(y, m, d).format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
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
        var shake by remember { mutableStateOf(prefs.shakeEnabled) }
        val headphones = remember { voice.headphonesConnected() }
        val lang by voice.lang.collectAsStateWithLifecycle()
        // Вернулись из установки голоса - перепроверить.
        LifecycleResumeEffect(Unit) {
            voice.recheckLanguage()
            onPauseOrDispose {}
        }
        SettingsCard(
            stringResource(R.string.voice_title),
            stringResource(if (headphones) R.string.headphones_on else R.string.headphones_off),
            help = stringResource(R.string.voice_desc) + "\n\n" + stringResource(R.string.shake_hint),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (enabled) R.string.enabled else R.string.disabled), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.voiceEnabled = it
                    HrService.refresh(this@MainActivity)
                })
            }
            Text(stringResource(R.string.voice_every), style = MaterialTheme.typography.bodyMedium)
            FitRow { style ->
                listOf(0, 1, 2, 5).forEach { v ->
                    val label = if (v == 0) stringResource(R.string.voice_on_change) else stringResource(R.string.minutes_n, v)
                    FilterChip(
                        selected = interval == v,
                        enabled = enabled,
                        onClick = {
                            interval = v
                            prefs.voiceIntervalMin = v
                        },
                        label = { Text(label, maxLines = 1, style = style) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.shake_label), Modifier.weight(1f))
                Switch(checked = shake, enabled = enabled, onCheckedChange = {
                    shake = it
                    prefs.shakeEnabled = it
                    HrService.refresh(this@MainActivity)
                })
            }
            if (lang == VoiceLang.ENGLISH || lang == VoiceLang.NONE) {
                Text(
                    stringResource(if (lang == VoiceLang.ENGLISH) R.string.voice_lang_english else R.string.voice_lang_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { installVoice(voice.enginePackage()) }) { Text(stringResource(R.string.voice_install)) }
            }
            OutlinedButton(onClick = { voice.test(LiveHr.state.value.bpm) }) { Text(stringResource(R.string.voice_test_btn)) }
        }
    }

    /**
     * Установка голосов у движка TTS по умолчанию (без пакета система спросит, каким из
     * движков); если он её не поддерживает - настройки синтеза речи.
     */
    private fun installVoice(engine: String?) {
        runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(engine)) }
            .recoverCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsTab(onChangeDevice: () -> Unit) {
        val page = settingsPage
        if (page == null) {
            SettingsGrid()
            return
        }
        // Страница пункта: назад - к плиткам, и стрелкой в заголовке, и системным жестом.
        BackHandler { settingsPage = null }
        CompositionLocalProvider(LocalSettingsBack provides { settingsPage = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = !wheelTouched),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    SettingsPage.SENSOR -> SensorSettings(onChangeDevice)
                    SettingsPage.ABOUT -> AboutSettings()
                    SettingsPage.SPEED -> SpeedSettings()
                    SettingsPage.ALARM -> AlarmSettings()
                    SettingsPage.NIGHT -> NightSettings()
                    SettingsPage.VOICE -> VoiceSettings()
                    SettingsPage.NOTIFICATION -> NotificationSettings()
                    SettingsPage.THEME -> ThemeSettings()
                    SettingsPage.LANGUAGE -> LanguageSettings()
                    SettingsPage.HISTORY -> HistorySettings()
                    SettingsPage.HEALTH -> HealthSettings()
                }
            }
        }
    }

    /**
     * Главная страница настроек: плитки в два столбца со значком, названием и коротким
     * статусом. Над ними - то, что требует действия (нет прав на Now Bar, ограничения батареи).
     */
    @Composable
    private fun SettingsGrid() {
        val live by LiveHr.state.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (Build.VERSION.SDK_INT >= 36 && !promotedAllowed) {
                SettingsCard(stringResource(R.string.nowbar_title), stringResource(R.string.nowbar_off)) {
                    Button(onClick = ::openPromotionSettings) { Text(stringResource(R.string.enable)) }
                }
            }
            if (!batteryUnrestricted) {
                SettingsCard(stringResource(R.string.bg_title), stringResource(R.string.bg_bad)) {
                    Button(onClick = ::requestBatteryUnrestricted) { Text(stringResource(R.string.battery_unrestrict)) }
                }
            }
            // Выбор языка приложения есть в системе только с Android 13.
            val pages = SettingsPage.entries.filter { it != SettingsPage.LANGUAGE || Build.VERSION.SDK_INT >= 33 }
            pages.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { p -> SettingsTile(p, tileStatus(p, live), Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun SettingsTile(p: SettingsPage, status: String, modifier: Modifier) {
        Card(onClick = { settingsPage = p }, modifier = modifier) {
            // Значок и название в строку, статус под ними: так десять плиток встают на один экран.
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(32.dp).background(p.color.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(p.icon), null, tint = p.color, modifier = Modifier.size(20.dp))
                    }
                    // Длинное название ("Health Connect") уменьшается, а не обрезается.
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleSmall) {
                        OneLineText(tileTitle(p), Modifier.weight(1f))
                    }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }

    /** Короткий статус пункта для плитки: главное значение, чтобы не открывать пункт зря. */
    @Composable
    private fun tileStatus(p: SettingsPage, live: LiveState): String = when (p) {
        SettingsPage.SENSOR -> stringResource(
            when {
                released -> R.string.tile_sensor_released
                live.conn == ConnState.IDLE -> R.string.tile_sensor_stopped
                else -> R.string.tile_sensor_running
            }
        )
        SettingsPage.ABOUT -> {
            val h = prefs.heightCm
            val a = age
            when {
                a != null && h > 0 -> stringResource(R.string.tile_about, a, h)
                a != null -> stringResource(R.string.tile_about_no_height, a)
                h > 0 -> stringResource(R.string.tile_about_no_age, h)
                else -> stringResource(R.string.tile_about_empty)
            }
        }
        SettingsPage.SPEED -> {
            // Скорость бывает по шагомеру (нужен рост) и по GPS в тренировке - независимо друг от друга.
            val steps = prefs.stepsEnabled && stepsGranted
            val gps = prefs.gpsInTraining && locationGranted
            when {
                steps && prefs.heightCm <= 0 -> stringResource(R.string.tile_speed_no_height)
                steps && gps -> stringResource(R.string.tile_speed_gps)
                gps -> stringResource(R.string.tile_speed_gps_only)
                steps -> stringResource(R.string.tile_speed_steps)
                else -> stringResource(R.string.tile_speed_off)
            }
        }
        SettingsPage.ALARM -> prefs.range(profile).let { r ->
            if (prefs.alarmEnabled(profile)) stringResource(R.string.tile_alarm, stringResource(profile.label), r.first, r.last)
            else stringResource(R.string.tile_alarm_off, stringResource(profile.label))
        }
        // Часы ночи действуют и без тишины вибрации: по ним граница сна.
        SettingsPage.NIGHT -> "%02d:%02d-%02d:%02d".format(prefs.nightFrom / 60, prefs.nightFrom % 60, prefs.nightTo / 60, prefs.nightTo % 60)
        SettingsPage.VOICE -> when {
            !prefs.voiceEnabled -> stringResource(R.string.disabled)
            prefs.voiceIntervalMin == 0 -> stringResource(R.string.tile_voice_events)
            else -> stringResource(R.string.tile_voice_every, prefs.voiceIntervalMin)
        }
        SettingsPage.NOTIFICATION -> stringResource(if (prefs.showInNotification) R.string.tile_notif_on else R.string.tile_notif_off)
        SettingsPage.THEME -> stringResource(
            when (themeMode) {
                Prefs.THEME_LIGHT -> R.string.theme_light
                Prefs.THEME_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
        SettingsPage.LANGUAGE -> APP_LANGUAGES.firstOrNull { it.first == appLanguage }?.second ?: systemLanguageLabel()
        SettingsPage.HISTORY -> {
            val ctx = LocalContext.current
            val count by remember { HrDb.get(ctx).dao().count() }.collectAsState(0)
            stringResource(R.string.tile_history, count)
        }
        SettingsPage.HEALTH -> when {
            !HealthSync.isAvailable(this) -> stringResource(R.string.tile_hc_unavailable)
            hcEnabled && hcGranted && hcSyncedUntil > 0 ->
                stringResource(R.string.tile_hc_sent, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(hcSyncedUntil)))
            hcEnabled && hcGranted -> stringResource(R.string.hc_nothing_sent)
            else -> stringResource(R.string.tile_hc_off)
        }
    }

    @Composable
    private fun SensorSettings(onChangeDevice: () -> Unit) {
        var search by remember { mutableStateOf(prefs.sensorSearchMin) }
        SettingsCard(
            stringResource(R.string.sensor_title),
            prefs.deviceName ?: prefs.deviceAddress ?: stringResource(R.string.sensor_none),
            help = stringResource(R.string.sensor_search_hint),
        ) {
            OutlinedButton(onClick = onChangeDevice) { Text(stringResource(R.string.change_sensor)) }
            val live by LiveHr.state.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.collect_switch), Modifier.weight(1f))
                Switch(checked = live.conn != ConnState.IDLE, onCheckedChange = {
                    if (it) startCollecting() else HrService.stop(this@MainActivity)
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.release_switch), Modifier.weight(1f))
                Switch(checked = released, onCheckedChange = {
                    autoJob?.cancel()
                    released = it
                    if (it) {
                        HrService.release(this@MainActivity)
                    } else {
                        prefs.released = false
                        autoStartIfNearby()
                    }
                })
            }
            if (released) {
                Text(
                    stringResource(R.string.released_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.sensor_search, search), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = search.toFloat(),
                onValueChange = {
                    search = it.roundToInt()
                    prefs.sensorSearchMin = search
                },
                valueRange = SEARCH_MIN_MIN.toFloat()..SEARCH_MAX_MIN.toFloat(),
                steps = SEARCH_MAX_MIN - SEARCH_MIN_MIN - 1,
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HealthSettings() {
        val timeFmt = remember { SimpleDateFormat(dayMonthPattern() + " HH:mm", Locale.getDefault()) }
        // Отправку делает сервис; пока карточка на экране, перечитываем отметку сами.
        LaunchedEffect(Unit) {
            while (true) {
                hcSyncedUntil = prefs.hcSyncedUntil
                delay(30_000)
            }
        }
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
                        FitRow { style ->
                            OutlinedButton(enabled = !hcBusy, onClick = ::syncHealthNow) {
                                Text(stringResource(R.string.hc_send_now), maxLines = 1, style = style)
                            }
                            OutlinedButton(onClick = {
                                prefs.hcEnabled = false
                                refreshHealth()
                            }) { Text(stringResource(R.string.hc_disable), maxLines = 1, style = style) }
                        }
                        hcResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        OutlinedButton(onClick = {
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
    }

    @Composable
    private fun ThemeSettings() {
        SettingsCard(stringResource(R.string.theme_title), help = stringResource(R.string.theme_desc)) {
            FitRow { style ->
                listOf(
                    Prefs.THEME_SYSTEM to R.string.theme_system,
                    Prefs.THEME_LIGHT to R.string.theme_light,
                    Prefs.THEME_DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            prefs.theme = mode
                        },
                        label = { Text(stringResource(label), maxLines = 1, style = style) },
                    )
                }
            }
        }
    }

    /**
     * Заголовок плитки. У языка он всегда по-английски: попавший в незнакомый язык
     * найдёт плитку по слову "Language". Двуязычный заголовок в плитку не влезает.
     */
    @Composable
    private fun tileTitle(p: SettingsPage): String =
        if (p == SettingsPage.LANGUAGE) LANGUAGE_EN else stringResource(p.title)

    /** Язык хранит система (LocaleManager): тот же выбор виден в настройках Android. */
    private fun currentAppLanguage(): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        val list = getSystemService(LocaleManager::class.java).applicationLocales
        return if (list.isEmpty) "" else list[0].toLanguageTag()
    }

    /**
     * "Системный (Русский)": слово "системный" - на текущем языке приложения, а язык
     * системы - самоназванием, чтобы его узнал тот, кто в текущем языке не читает.
     */
    @Composable
    private fun systemLanguageLabel(): String {
        val word = stringResource(R.string.lang_system)
        if (Build.VERSION.SDK_INT < 33) return word
        val sys = getSystemService(LocaleManager::class.java).systemLocales
        if (sys.isEmpty) return word
        val own = sys[0].getDisplayLanguage(sys[0]).replaceFirstChar { it.titlecase(sys[0]) }
        return "$word ($own)"
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun LanguageSettings() {
        SettingsCard(stringResource(R.string.lang_title), help = stringResource(R.string.lang_desc)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("" to systemLanguageLabel()) + APP_LANGUAGES).forEach { (tag, name) ->
                    FilterChip(
                        selected = appLanguage == tag,
                        onClick = {
                            if (appLanguage == tag || Build.VERSION.SDK_INT < 33) return@FilterChip
                            appLanguage = tag
                            // Система сама пересоздаст экран уже на новом языке.
                            getSystemService(LocaleManager::class.java).applicationLocales =
                                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                        },
                        label = { Text(name, maxLines = 1) },
                    )
                }
            }
        }
    }

    @Composable
    private fun NotificationSettings() {
        var show by remember { mutableStateOf(prefs.showInNotification) }
        SettingsCard(
            stringResource(R.string.notif_setting_title),
            help = stringResource(if (show) R.string.notif_setting_on else R.string.notif_setting_off),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (show) R.string.enabled else R.string.disabled), Modifier.weight(1f))
                Switch(checked = show, onCheckedChange = {
                    show = it
                    prefs.showInNotification = it
                    HrService.refresh(this@MainActivity)
                })
            }
        }
    }

    @Composable
    /**
     * Карточка настроек. text - короткий статус, виден всегда; help - пояснение, скрыто
     * и раскрывается значком рядом с заголовком, чтобы не занимать место.
     */
    private fun SettingsCard(
        title: String,
        text: String? = null,
        help: String? = null,
        /** Дополнение к пояснению, которое не уложить в текст (например, таблица). */
        helpExtra: (@Composable () -> Unit)? = null,
        actions: @Composable () -> Unit,
    ) {
        var showHelp by rememberSaveable(title) { mutableStateOf(false) }
        val back = LocalSettingsBack.current
        val body: @Composable () -> Unit = {
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
            if (help != null && showHelp) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                helpExtra?.invoke()
            }
            actions()
        }
        val helpButton: @Composable () -> Unit = {
            if (help != null) {
                IconButton(onClick = { showHelp = !showHelp }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (showHelp) Icons.Filled.Info else Icons.Outlined.Info,
                        stringResource(R.string.help_toggle),
                        tint = if (showHelp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (back != null) {
            // Отдельная страница пункта: заголовок со стрелкой назад, содержимое - в плитке,
            // как на главной странице настроек.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    helpButton()
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
                }
            }
            return
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    helpButton()
                }
                body()
            }
        }
    }

    @Composable
    private fun DevicePicker(onPick: (FoundDevice) -> Unit, onCancel: (() -> Unit)?) {
        val ctx = LocalContext.current
        val scanner = remember { HrScanner(ctx) }
        val found = remember { mutableStateMapOf<String, FoundDevice>() }
        var showAll by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        // Номер попытки поиска: "Искать заново" начинает поиск с пустого списка.
        var round by remember { mutableIntStateOf(0) }
        // Частые запуски поиска система молча глушит; после нажатия кнопка отдыхает.
        var rescanAt by remember { mutableLongStateOf(0L) }
        // Раз в пару секунд: включили ли Bluetooth и какие устройства пропали из эфира.
        val tick by produceState(SystemClock.elapsedRealtime()) {
            while (true) {
                delay(2_000)
                value = SystemClock.elapsedRealtime()
            }
        }
        val btOn = remember(tick) { scanner.isBluetoothOn }

        DisposableEffect(btOn, round) {
            var job: Job? = null
            found.clear()
            failed = false
            if (btOn) {
                job = lifecycleScope.launch {
                    runCatching { scanner.scan().collect { found.putAll(it) } }
                        .onFailure { if (it is ScanFailed) failed = true else if (it is CancellationException) throw it }
                }
            }
            onDispose { job?.cancel() }
        }

        Text(stringResource(R.string.pick_title), style = MaterialTheme.typography.titleLarge)
        if (!btOn) {
            Text(stringResource(R.string.bt_off))
        } else {
            Text(
                stringResource(R.string.pick_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (failed) Text(stringResource(R.string.scan_failed), style = MaterialTheme.typography.bodySmall)
        FitRow { style ->
            onCancel?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.cancel), maxLines = 1, style = style) } }
            OutlinedButton(enabled = btOn && tick - rescanAt >= RESCAN_PAUSE_MS, onClick = {
                rescanAt = SystemClock.elapsedRealtime()
                round++
            }) {
                Text(stringResource(R.string.scan_again), maxLines = 1, style = style)
            }
        }
        // Датчик, который не слышно дольше FOUND_STALE_MS, выключен или ушёл к другому телефону.
        val fresh = found.values.filter { tick - it.seenAt < FOUND_STALE_MS }
        val others = fresh.count { !it.isHrSensor }
        val list = fresh
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
