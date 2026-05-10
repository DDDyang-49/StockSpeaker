package com.stockspeaker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

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
    var aiKey by remember { mutableStateOf(cfg.aiApiKey) }
    var aiInterval by remember { mutableStateOf(cfg.aiSummaryInterval.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var aiTwoEnabled by remember { mutableStateOf(cfg.aiTwoEnabled) }
    var aiTwoProvider by remember { mutableStateOf(cfg.aiTwoProvider) }
    var aiTwoKey by remember { mutableStateOf(cfg.aiTwoApiKey) }
    var showKey2 by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

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
            aiProvider = aiProvider, aiApiUrl = pi.url, aiModel = pi.model,
            aiSummaryInterval = aiInterval.toIntOrNull() ?: 5,
            aiTwoEnabled = aiTwoEnabled, aiTwoApiKey = twoKey,
            aiTwoProvider = aiTwoProvider, aiTwoApiUrl = pi2.url, aiTwoModel = pi2.model,
            monitoringActive = true
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
                aiEnabled, { aiEnabled = it }, aiProvider, { aiProvider = it },
                aiKey, { aiKey = it }, aiInterval, { aiInterval = it },
                showKey, { showKey = it }, aiTwoEnabled, { aiTwoEnabled = it },
                aiTwoProvider, { aiTwoProvider = it }, aiTwoKey, { aiTwoKey = it },
                showKey2, { showKey2 = it }, { buildConfig() }, configManager)
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
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 行情区
        Column {
            Spacer(Modifier.height(8.dp))
            if (state.isRunning && state.stockName.isNotEmpty()) {
                PriceCard(state)
            } else {
                // 未启动时的占位
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
        }

        // 按钮区
        Column {
            // 异动通知提示
            if (state.isRunning && state.statusText.contains("AI异动")) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StockColors.priceUpBg,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "点击通知栏「关闭提醒」可退出异动播报",
                        modifier = Modifier.padding(10.dp),
                        fontSize = 12.sp, color = StockColors.priceUp, textAlign = TextAlign.Center
                    )
                }
            }

            Button(
                onClick = {
                    if (state.isRunning) StockMonitorService.stop(ctx)
                    else {
                        if (code.isBlank()) return@Button
                        configManager.save(buildConfig())
                        StockMonitorService.start(ctx)
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
    aiKey: String, onAiKey: (String) -> Unit,
    aiInterval: String, onAiInterval: (String) -> Unit,
    showKey: Boolean, onShowKey: (Boolean) -> Unit,
    aiTwoEnabled: Boolean, onAiTwo: (Boolean) -> Unit,
    aiTwoProvider: String, onAiTwoProvider: (String) -> Unit,
    aiTwoKey: String, onAiTwoKey: (String) -> Unit,
    showKey2: Boolean, onShowKey2: (Boolean) -> Unit,
    buildConfig: () -> AppConfig,
    configManager: ConfigManager
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        // ── 盯盘配置 ──
        Section("盯盘配置") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelField("股票代码", value = code, onValue = onCode, enabled = enabled, Modifier.weight(1f))
                LabelField("播报间隔", value = interval, onValue = onInterval, enabled = enabled, Modifier.weight(1f), suffix = "秒")
            }
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
        Section("AI 辅助盘面分析") {
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
            LabelField("API Key", value = aiKey, onValue = onAiKey, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                isPassword = !showKey, trailing = { TextButton("显示") { onShowKey(!showKey) } })
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
        Section("辅AI 资金面分析（混沌/长间隔时触发）") {
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
            LabelField("API Key（留空则复用主AI）", value = aiTwoKey, onValue = onAiTwoKey, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                isPassword = !showKey2, trailing = { TextButton("显示") { onShowKey2(!showKey2) } })
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

        // ── 保存按钮 ──
        Button(
            onClick = { configManager.save(buildConfig()) },
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp
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

// ── 行情卡片 ──

@Composable
private fun PriceCard(state: ServiceUiState) {
    val isUp = state.changePct > 0
    val isDown = state.changePct < 0
    val priceColor = when { isUp -> StockColors.priceUp; isDown -> StockColors.priceDown; else -> StockColors.priceFlat }

    // 价格变化闪烁动画
    val flashBg by animateColorAsState(
        targetValue = when { isUp -> StockColors.priceUpBg; isDown -> StockColors.priceDownBg; else -> Color.Transparent },
        animationSpec = tween(300)
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(20.dp).background(flashBg, RoundedCornerShape(14.dp))) {
            // 名称行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.stockName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(10.dp))
                Surface(shape = RoundedCornerShape(6.dp),
                    color = if (isUp) StockColors.priceUpBg else if (isDown) StockColors.priceDownBg else Color.Transparent
                ) {
                    Text(if (isUp) "涨${abs(state.changePct)}%" else if (isDown) "跌${abs(state.changePct)}%" else "平",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = priceColor)
                }
            }
            Spacer(Modifier.height(14.dp))

            // 价格
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥", fontSize = 20.sp, fontWeight = FontWeight.Light, color = priceColor, modifier = Modifier.padding(bottom = 5.dp))
                Text(state.price.toString(), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = priceColor, fontFamily = FontFamily.Monospace)
            }

            if (abs(state.speed) > 0) {
                Text("涨速 ${state.speed}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // 数据行
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DataItem("成交额", state.amount)
                DataItem("量比", state.volRatio.toString())
                DataItem("现手", state.currentHand.toString())
            }

            // 大单
            if (state.largeAsks.isNotEmpty() || state.largeBids.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                if (state.largeAsks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("▼ ", fontSize = 12.sp, color = StockColors.priceDown, fontWeight = FontWeight.Bold)
                        Text(state.largeAsks.joinToString("  "), fontSize = 13.sp, color = StockColors.priceDown)
                    }
                }
                if (state.largeBids.isNotEmpty()) {
                    if (state.largeAsks.isNotEmpty()) Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("▲ ", fontSize = 12.sp, color = StockColors.priceUp, fontWeight = FontWeight.Bold)
                        Text(state.largeBids.joinToString("  "), fontSize = 13.sp, color = StockColors.priceUp)
                    }
                }
            }
        }
    }
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
            state.statusText.contains("✗") || state.statusText.contains("⚠") -> StockColors.priceUpBg
            state.isRunning -> Color(0xFF1A3A2A)
            else -> Color(0xFF3A2A1A)
        }
        val textColor = when {
            state.statusText.contains("✗") || state.statusText.contains("⚠") -> StockColors.priceUp
            state.isRunning -> Color(0xFF34D399)
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
