package com.stockspeaker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.stockspeaker.ui.theme.StockColors
import com.stockspeaker.ui.theme.StockSpeakerTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)
        // v1.1.0+ 通知功能暂禁用
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        //     ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        // ) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        // 电池优化白名单：息屏保活关键，国产 ROM 会杀后台进程
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // 如果之前正在盯盘（被系统杀掉后返回），自动恢复
        if (configManager.load().monitoringActive) {
            StockMonitorService.start(this)
        }

        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" }
        catch (_: Exception) { "1.0.0" }

        setContent { StockSpeakerTheme { App(configManager, versionName) } }
    }
}

// ═══════════════════════════════════════════
// 主界面
// ═══════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(configManager: ConfigManager, versionName: String = "1.0.0") {
    val cfg = remember { configManager.load() }
    var code by remember { mutableStateOf(cfg.stockCode) }
    var interval by remember { mutableStateOf(cfg.speakInterval.toString()) }
    var threshold by remember { mutableStateOf(cfg.largeOrderThreshold.toString()) }
    var priceOpt by remember { mutableStateOf(cfg.speakPrice) }
    var pctOpt by remember { mutableStateOf(cfg.speakPct) }
    var speedOpt by remember { mutableStateOf(cfg.speakSpeed) }
    var amountOpt by remember { mutableStateOf(cfg.speakAmount) }
    var volRatioOpt by remember { mutableStateOf(cfg.speakVolRatio) }
    var handOpt by remember { mutableStateOf(cfg.speakCurrentHand) }
    var largeOrderOpt by remember { mutableStateOf(cfg.speakLargeOrders) }
    var transactionOpt by remember { mutableStateOf(cfg.speakTransactionDetail) }
    var aiEnabled by remember { mutableStateOf(cfg.aiEnabled) }
    var aiProvider by remember { mutableStateOf(cfg.aiProvider) }
    var aiModel by remember { mutableStateOf(cfg.aiModel) }
    var aiKey by remember { mutableStateOf(cfg.aiApiKey) }
    var aiInterval by remember { mutableStateOf(cfg.aiSummaryInterval.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var aiTwoEnabled by remember { mutableStateOf(cfg.aiTwoEnabled) }
    var aiTwoProvider by remember { mutableStateOf(cfg.aiTwoProvider) }
    var aiTwoModel by remember { mutableStateOf(cfg.aiTwoModel) }
    var aiTwoKey by remember { mutableStateOf(cfg.aiTwoApiKey) }
    var showKey2 by remember { mutableStateOf(false) }
    var aiKeyNote by remember {
        val entry = configManager.getApiKeyHistory().find { it.key == cfg.aiApiKey }
        mutableStateOf(entry?.note ?: "")
    }
    var aiTwoKeyNote by remember {
        val entry = configManager.getApiKeyHistory().find { it.key == cfg.aiTwoApiKey }
        mutableStateOf(entry?.note ?: "")
    }
    var stockSector by remember { mutableStateOf(cfg.stockSector) }
    var selectedTab by remember { mutableStateOf(0) }
    // v1.1.0: 新数据源开关（北向已砍——A股盘中不再披露实时北向资金）
    var fundFlowEnabled by remember { mutableStateOf(cfg.fundFlowEnabled) }
    var dragonTigerEnabled by remember { mutableStateOf(cfg.dragonTigerEnabled) }
    var conceptAutoDetect by remember { mutableStateOf(cfg.conceptAutoDetect) }
    var alertLimitDist by remember { mutableStateOf(cfg.alertLimitDistance) }

    val state by StockMonitorService.uiState.collectAsState()
    val ctx = LocalContext.current
    val enabled = !state.isRunning

    fun buildConfig(): AppConfig {
        val pi = AI_PROVIDERS.find { it.id == aiProvider } ?: AI_PROVIDERS[0]
        val pi2 = AI_PROVIDERS.find { it.id == aiTwoProvider } ?: AI_PROVIDERS[0]
        val twoKey = aiTwoKey.trim().ifBlank { aiKey.trim() }
        return AppConfig(
            stockCode = code.trim(), speakInterval = interval.toIntOrNull() ?: 15,
            largeOrderThreshold = threshold.toIntOrNull() ?: 500,
            speakPrice = priceOpt, speakPct = pctOpt,
            speakCurrentHand = handOpt, speakAmount = amountOpt,
            speakVolRatio = volRatioOpt, speakSpeed = speedOpt,
            speakLargeOrders = largeOrderOpt,
            speakTransactionDetail = transactionOpt,
            aiEnabled = aiEnabled, aiApiKey = aiKey.trim(),
            aiProvider = aiProvider, aiApiUrl = pi.url, aiModel = aiModel.trim().ifBlank { pi.model },
            aiThinkingModel = pi.thinkingModel,
            aiSummaryInterval = aiInterval.toIntOrNull() ?: 5,
            aiTwoEnabled = aiTwoEnabled, aiTwoApiKey = twoKey,
            aiTwoProvider = aiTwoProvider, aiTwoApiUrl = pi2.url, aiTwoModel = aiTwoModel.trim().ifBlank { pi2.model },
            aiTwoThinkingModel = pi2.thinkingModel,
            monitoringActive = true,
            stockSector = stockSector.trim(),
            fundFlowEnabled = fundFlowEnabled,
            dragonTigerEnabled = dragonTigerEnabled,
            conceptAutoDetect = conceptAutoDetect,
            alertLimitDistance = alertLimitDist
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("摸鱼听盘", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.isRunning) Color(0xFF34D399) else Color(0xFF6B7280)))
                            Spacer(Modifier.width(5.dp))
                            Text("v$versionName", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, "盯盘") },
                    label = { Text("盯盘") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Settings, "设置") },
                    label = { Text("设置") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        when (selectedTab) {
            0 -> MonitorTab(pad, state, enabled, ctx, code, { buildConfig() }, configManager)
            1 -> SettingsTab(pad, state, enabled, code, { code = it }, interval, { interval = it },
                threshold, { threshold = it }, priceOpt, { priceOpt = it }, pctOpt, { pctOpt = it },
                speedOpt, { speedOpt = it }, amountOpt, { amountOpt = it },
                volRatioOpt, { volRatioOpt = it }, handOpt, { handOpt = it },
                largeOrderOpt, { largeOrderOpt = it }, transactionOpt, { transactionOpt = it },
                aiEnabled, { aiEnabled = it }, aiProvider, { id -> aiProvider = id; aiModel = AI_PROVIDERS.find { it.id == id }?.model ?: "" },
                aiModel, { aiModel = it },
                aiKey, { aiKey = it }, aiInterval, { aiInterval = it },
                showKey, { showKey = it }, aiTwoEnabled, { aiTwoEnabled = it },
                aiTwoProvider, { id -> aiTwoProvider = id; aiTwoModel = AI_PROVIDERS.find { it.id == id }?.model ?: "" },
                aiTwoModel, { aiTwoModel = it },
                aiTwoKey, { aiTwoKey = it },
                showKey2, { showKey2 = it }, aiKeyNote, { aiKeyNote = it },
                aiTwoKeyNote, { aiTwoKeyNote = it }, stockSector, { stockSector = it },
                fundFlowEnabled, { fundFlowEnabled = it },
                dragonTigerEnabled, { dragonTigerEnabled = it },
                conceptAutoDetect, { conceptAutoDetect = it },
                alertLimitDist, { alertLimitDist = it },
                { buildConfig() }, configManager)
        }
    }
}

// ═══════════════════════════════════════════
// 盯盘页
// ═══════════════════════════════════════════

@Composable
private fun MonitorTab(
    pad: androidx.compose.foundation.layout.PaddingValues,
    state: ServiceUiState,
    enabled: Boolean,
    ctx: android.content.Context,
    code: String,
    buildConfig: () -> AppConfig,
    configManager: ConfigManager
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
    ) {
        // 行情区 + AI日志（可滚动，占据剩余空间）
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            if (state.isRunning && state.stockName.isNotEmpty()) {
                PriceCard(state)
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("📈", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("点击下方按钮开始盯盘", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            StatusSection(state)
            Spacer(Modifier.height(8.dp))
        }

        // 按钮区（永远吸底）
        Button(
            onClick = {
                if (state.isRunning) StockMonitorService.stop(ctx)
                else {
                    val rawCode = code.trim()
                    if (rawCode.isBlank()) return@Button
                    if (rawCode.all { it.isDigit() }) {
                        val cfg = buildConfig()
                        configManager.save(cfg)
                        configManager.addStockCodeToHistory(cfg.stockCode, cfg.stockCode)
                        StockMonitorService.start(ctx)
                    } else {
                        Thread {
                            val result = StockFetcher.searchStock(rawCode)
                            val resolved = result?.first ?: rawCode
                            val name = result?.second ?: rawCode
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val cfg = buildConfig().copy(stockCode = resolved)
                                configManager.save(cfg)
                                configManager.addStockCodeToHistory(resolved, name)
                                StockMonitorService.start(ctx)
                            }
                        }.start()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isRunning) Color(0xFF991B1B) else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (state.isRunning) "停止盯盘" else "开始自动盯盘",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ═══════════════════════════════════════════
// 设置页
// ═══════════════════════════════════════════

@Composable
private fun SettingsTab(
    pad: androidx.compose.foundation.layout.PaddingValues,
    state: ServiceUiState,
    enabled: Boolean,
    code: String, onCode: (String) -> Unit,
    interval: String, onInterval: (String) -> Unit,
    threshold: String, onThreshold: (String) -> Unit,
    priceOpt: Boolean, onPrice: (Boolean) -> Unit,
    pctOpt: Boolean, onPct: (Boolean) -> Unit,
    speedOpt: Boolean, onSpeed: (Boolean) -> Unit,
    amountOpt: Boolean, onAmount: (Boolean) -> Unit,
    volRatioOpt: Boolean, onVolRatio: (Boolean) -> Unit,
    handOpt: Boolean, onHand: (Boolean) -> Unit,
    largeOrderOpt: Boolean, onLargeOrder: (Boolean) -> Unit,
    transactionOpt: Boolean, onTransaction: (Boolean) -> Unit,
    aiEnabled: Boolean, onAi: (Boolean) -> Unit,
    aiProvider: String, onAiProvider: (String) -> Unit,
    aiModel: String, onAiModel: (String) -> Unit,
    aiKey: String, onAiKey: (String) -> Unit,
    aiInterval: String, onAiInterval: (String) -> Unit,
    showKey: Boolean, onShowKey: (Boolean) -> Unit,
    aiTwoEnabled: Boolean, onAiTwo: (Boolean) -> Unit,
    aiTwoProvider: String, onAiTwoProvider: (String) -> Unit,
    aiTwoModel: String, onAiTwoModel: (String) -> Unit,
    aiTwoKey: String, onAiTwoKey: (String) -> Unit,
    showKey2: Boolean, onShowKey2: (Boolean) -> Unit,
    aiKeyNote: String, onAiKeyNote: (String) -> Unit,
    aiTwoKeyNote: String, onAiTwoKeyNote: (String) -> Unit,
    stockSector: String, onStockSector: (String) -> Unit,
    fundFlowEnabled: Boolean, onFundFlow: (Boolean) -> Unit,
    dragonTigerEnabled: Boolean, onDragonTiger: (Boolean) -> Unit,
    conceptAutoDetect: Boolean, onConcept: (Boolean) -> Unit,
    alertLimitDist: Boolean, onAlertLimit: (Boolean) -> Unit,
    buildConfig: () -> AppConfig,
    configManager: ConfigManager
) {
    val apiKeyHistory = remember { configManager.getApiKeyHistory() }
    val codeHistory = remember { configManager.getStockCodeHistory() }
    var apiKeyExpanded by remember { mutableStateOf(false) }
    var apiKey2Expanded by remember { mutableStateOf(false) }
    var codeExpanded by remember { mutableStateOf(false) }

    fun maskKey(k: String): String {
        if (k.length <= 8) return k
        return k.take(4) + "..." + k.takeLast(4)
    }

    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        // ── 盯盘配置 ──
        Section("盯盘配置") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 代码输入 + 历史下拉
                Box(Modifier.weight(1f)) {
                    LabelField("代码/名称", value = code, onValue = onCode, enabled = enabled, Modifier.fillMaxWidth(),
                        trailing = if (codeHistory.isNotEmpty()) {
                            { IconButton(onClick = { codeExpanded = true }) { Icon(Icons.Filled.ArrowDropDown, "历史") } }
                        } else null
                    )
                    DropdownMenu(expanded = codeExpanded, onDismissRequest = { codeExpanded = false }) {
                        codeHistory.forEach { (c, n) ->
                            DropdownMenuItem(
                                text = { Text(if (n != c) "$n ($c)" else c, fontSize = 13.sp) },
                                onClick = { onCode(c); codeExpanded = false }
                            )
                        }
                    }
                }
                LabelField("播报间隔", value = interval, onValue = onInterval, enabled = enabled, Modifier.weight(1f), suffix = "秒")
            }
            Spacer(Modifier.height(10.dp))
            LabelField("所属题材（选填）", value = stockSector, onValue = onStockSector, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            LabelField("大单阈值", value = threshold, onValue = onThreshold, enabled = enabled, modifier = Modifier.fillMaxWidth(), suffix = "手")
        }
        Spacer(Modifier.height(12.dp))

        // ── 播报内容 ──
        Section("播报内容") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Cb("现价", priceOpt, enabled, onPrice); Cb("涨幅", pctOpt, enabled, onPct); Cb("涨速", speedOpt, enabled, onSpeed) }
                Column(Modifier.weight(1f)) { Cb("成交额", amountOpt, enabled, onAmount); Cb("量比", volRatioOpt, enabled, onVolRatio) }
                Column(Modifier.weight(1f)) { Cb("现手", handOpt, enabled, onHand); Cb("盘口大单", largeOrderOpt, enabled, onLargeOrder); Cb("成交明细", transactionOpt, enabled, onTransaction) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── AI 辅助 ──
        Section("AI 辅助分析") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AI_PROVIDERS.forEach { p ->
                    FilterChip(
                        selected = aiProvider == p.id,
                        onClick = { onAiProvider(p.id) },
                        label = { Text(p.displayName, fontSize = 12.sp) },
                        enabled = enabled
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LabelField("模型名称", value = aiModel, onValue = onAiModel, enabled = enabled, modifier = Modifier.fillMaxWidth())
            if (aiProvider == "edgefn") {
                Text("EdgeFn 仅支持 Qwen3-235B-A22B-2507，其他模型会 403/timeout",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            // API Key + 历史下拉
            Box(Modifier.fillMaxWidth()) {
                LabelField("API Key", value = aiKey, onValue = onAiKey, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    isPassword = !showKey, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (apiKeyHistory.isNotEmpty()) {
                                IconButton(onClick = { apiKeyExpanded = true }) { Icon(Icons.Filled.ArrowDropDown, "历史") }
                            }
                            TextButton("显示") { onShowKey(!showKey) }
                        }
                    }
                )
                DropdownMenu(expanded = apiKeyExpanded, onDismissRequest = { apiKeyExpanded = false }) {
                    apiKeyHistory.forEach { e ->
                        val label = maskKey(e.key) + if (e.note.isNotBlank()) "  |  ${e.note}" else ""
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            onClick = { onAiKey(e.key); onAiKeyNote(e.note); apiKeyExpanded = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LabelField("备注", value = aiKeyNote, onValue = onAiKeyNote, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = aiEnabled, onCheckedChange = onAi, enabled = enabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                    Text("启用AI", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (aiEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabelField("", value = aiInterval, onValue = onAiInterval, enabled = enabled, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
                    Text("次播报", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 辅 AI ──
        Section("辅AI · 资金面分析") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AI_PROVIDERS.forEach { p ->
                    FilterChip(
                        selected = aiTwoProvider == p.id,
                        onClick = { onAiTwoProvider(p.id) },
                        label = { Text(p.displayName, fontSize = 12.sp) },
                        enabled = enabled
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LabelField("模型名称", value = aiTwoModel, onValue = onAiTwoModel, enabled = enabled, modifier = Modifier.fillMaxWidth())
            if (aiTwoProvider == "edgefn") {
                Text("EdgeFn 仅支持 Qwen3-235B-A22B-2507，其他模型会 403/timeout",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            // 辅AI Key + 历史下拉
            Box(Modifier.fillMaxWidth()) {
                LabelField("API Key（留空复用主AI）", value = aiTwoKey, onValue = onAiTwoKey, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    isPassword = !showKey2, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (apiKeyHistory.isNotEmpty()) {
                                IconButton(onClick = { apiKey2Expanded = true }) { Icon(Icons.Filled.ArrowDropDown, "历史") }
                            }
                            TextButton("显示") { onShowKey2(!showKey2) }
                        }
                    }
                )
                DropdownMenu(expanded = apiKey2Expanded, onDismissRequest = { apiKey2Expanded = false }) {
                    apiKeyHistory.forEach { e ->
                        val label = maskKey(e.key) + if (e.note.isNotBlank()) "  |  ${e.note}" else ""
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            onClick = { onAiTwoKey(e.key); onAiTwoKeyNote(e.note); apiKey2Expanded = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LabelField("备注", value = aiTwoKeyNote, onValue = onAiTwoKeyNote, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = aiTwoEnabled, onCheckedChange = onAiTwo, enabled = enabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                    Text("启用辅AI", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 数据源增强（v1.1.0） ──
        Section("数据源增强") {
            Text("开启后播报和AI分析包含更多维度信息",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Cb("资金流向（含防伪）", fundFlowEnabled, enabled, onFundFlow)
                    Cb("龙虎榜（静态标签）", dragonTigerEnabled, enabled, onDragonTiger)
                }
                Column(Modifier.weight(1f)) {
                    Cb("自动识别题材", conceptAutoDetect, enabled, onConcept)
                    Cb("涨跌停预警", alertLimitDist, enabled, onAlertLimit)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (conceptAutoDetect) {
                Text("开启后自动识别题材，无需手动填写",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 保存按钮 ──
        Button(
            onClick = {
                val cfg = buildConfig()
                configManager.save(cfg)
                if (cfg.aiApiKey.isNotBlank()) configManager.addApiKeyToHistory(cfg.aiApiKey, aiKeyNote)
                if (cfg.aiTwoApiKey.isNotBlank()) configManager.addApiKeyToHistory(cfg.aiTwoApiKey, aiTwoKeyNote)
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("保存配置", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))

        // ── AI 日志 ──
        if (state.isRunning && state.aiLog.isNotEmpty()) {
            Section("AI 日志") {
                Text(
                    state.aiLog.takeLast(30).joinToString("\n"),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ═══════════════════════════════════════════
// 组件
// ═══════════════════════════════════════════

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(14.dp), color = bg,
        border = null,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun LabelField(
    label: String, value: String, onValue: (String) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, suffix: String? = null,
    isPassword: Boolean = false, textAlign: TextAlign = TextAlign.Start,
    trailing: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = if (label.isNotEmpty()) {{ Text(label, fontSize = 13.sp) }} else null,
        enabled = enabled, modifier = modifier, singleLine = true,
        shape = RoundedCornerShape(10.dp), suffix = suffix?.let {{ Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }},
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = textAlign),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun Cb(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = MaterialTheme.colorScheme.outline))
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text, fontSize = 12.sp) }
}

// ── 行情卡片 "Terminal Ticker" ──

@Composable
private fun PriceCard(state: ServiceUiState) {
    val isUp = state.changePct > 0
    val isDown = state.changePct < 0
    val priceColor = when { isUp -> StockColors.priceUp; isDown -> StockColors.priceDown; else -> StockColors.priceFlat }
    val borderColor = when { isUp -> StockColors.priceUpBg; isDown -> StockColors.priceDownBg; else -> StockColors.cardBorder }

    val flashBg by animateColorAsState(
        targetValue = when { isUp -> StockColors.priceUpBg; isDown -> StockColors.priceDownBg; else -> Color.Transparent },
        animationSpec = tween(200)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, RoundedCornerShape(2.dp))
            .background(StockColors.cardSurface, RoundedCornerShape(2.dp))
            .background(flashBg, RoundedCornerShape(2.dp))
            .padding(20.dp)
    ) {
        // 头部：名称 + 涨跌幅标签
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.stockName,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = when {
                    isUp -> StockColors.priceUpBg
                    isDown -> StockColors.priceDownBg
                    else -> Color.Transparent
                }
            ) {
                Text(
                    when {
                        isUp -> "▲ ${"%.2f".format(abs(state.changePct))}%"
                        isDown -> "▼ ${"%.2f".format(abs(state.changePct))}%"
                        else -> "─ 0.00%"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = priceColor, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 大号价格
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "¥",
                fontSize = 22.sp, fontWeight = FontWeight.Light,
                color = priceColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                "%.2f".format(state.price),
                fontSize = 44.sp, fontWeight = FontWeight.Bold,
                color = priceColor, fontFamily = FontFamily.Monospace,
                letterSpacing = (-1).sp
            )
        }

        // 涨速
        if (abs(state.speed) > 0.01) {
            val speedDir = if (state.speed > 0) "↑" else "↓"
            Text(
                "$speedDir 涨速 ${"%.2f".format(abs(state.speed))}%",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = StockColors.cardBorder, thickness = 0.5.dp)
        Spacer(Modifier.height(12.dp))

        // 数据网格
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DataItem("成交额", state.amount)
            DataItem("量比", "%.2f".format(state.volRatio))
            DataItem("现手", formatHand(state.currentHand))
        }

        // 大单盘口
        if (state.largeAsks.isNotEmpty() || state.largeBids.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = StockColors.cardBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))
            if (state.largeAsks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("▼", fontSize = 11.sp, color = StockColors.priceDown, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.largeAsks.joinToString("  "),
                        fontSize = 12.sp, color = StockColors.priceDown,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (state.largeBids.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("▲", fontSize = 11.sp, color = StockColors.priceUp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.largeBids.joinToString("  "),
                        fontSize = 12.sp, color = StockColors.priceUp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun formatHand(hand: Int): String = when {
    hand >= 10000 -> "${hand / 10000}万"
    hand >= 1000 -> "${hand / 1000}k"
    else -> hand.toString()
}

@Composable
private fun DataItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 状态栏 ──

@Composable
private fun StatusSection(state: ServiceUiState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val bgColor = when {
            state.statusText.contains("✗") || state.statusText.contains("⚠") -> Color(0xFFFDE8E8)
            state.isRunning -> Color(0xFFE6F9F0)
            else -> Color(0xFFFFF8E6)
        }
        val textColor = when {
            state.statusText.contains("✗") || state.statusText.contains("⚠") -> StockColors.priceUp
            state.isRunning -> StockColors.priceDown
            else -> StockColors.accentGold
        }
        Surface(shape = RoundedCornerShape(20.dp), color = bgColor) {
            Text(
                text = if (state.isRunning && state.lastSpeakTime.isNotEmpty()) "上次播报 ${state.lastSpeakTime}"
                       else state.statusText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textColor, textAlign = TextAlign.Center
            )
        }

        if (state.isRunning && state.aiLog.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val bg = MaterialTheme.colorScheme.surfaceVariant
            Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("AI 日志", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(state.aiLog.takeLast(20).joinToString("\n"), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
