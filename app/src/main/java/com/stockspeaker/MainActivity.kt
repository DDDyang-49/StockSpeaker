package com.stockspeaker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.stockspeaker.ui.theme.AppColors
import com.stockspeaker.ui.theme.StockSpeakerTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        setContent {
            StockSpeakerTheme {
                StockSpeakerApp(configManager, versionName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockSpeakerApp(configManager: ConfigManager, versionName: String) {
    val initialConfig = remember { configManager.load() }

    var stockCode by remember { mutableStateOf(initialConfig.stockCode) }
    var speakInterval by remember { mutableStateOf(initialConfig.speakInterval.toString()) }
    var largeThreshold by remember { mutableStateOf(initialConfig.largeOrderThreshold.toString()) }
    var speakPrice by remember { mutableStateOf(initialConfig.speakPrice) }
    var speakPct by remember { mutableStateOf(initialConfig.speakPct) }
    var speakSpeed by remember { mutableStateOf(initialConfig.speakSpeed) }
    var speakAmount by remember { mutableStateOf(initialConfig.speakAmount) }
    var speakVolRatio by remember { mutableStateOf(initialConfig.speakVolRatio) }
    var speakCurrentHand by remember { mutableStateOf(initialConfig.speakCurrentHand) }
    var speakLargeOrders by remember { mutableStateOf(initialConfig.speakLargeOrders) }

    val uiState by StockMonitorService.uiState.collectAsState()
    var showTipsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)

    if (showTipsDialog) {
        AlertDialog(
            onDismissRequest = { showTipsDialog = false },
            title = { Text("安卓防掉后台指南", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "受安卓限制，无原生后台服务极易被系统清理。如果您希望应用能在后台持续播报，请务必在手机设置：\n\n" +
                    "1. 任务列表给本App加【锁】\n" +
                    "2. 电池优化设为【无限制】\n" +
                    "3. 开启【自启动】与【允许后台活动】",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showTipsDialog = false }) { Text("我知道了") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("摸鱼听盘", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "v$versionName",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showTipsDialog = true }) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            val controlsEnabled = !uiState.isRunning

            // ---- 配置卡片 ----
            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle("盯盘配置")

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = stockCode,
                            onValueChange = { stockCode = it },
                            label = { Text("股票代码") },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors()
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedTextField(
                            value = speakInterval,
                            onValueChange = { speakInterval = it },
                            label = { Text("播报间隔") },
                            suffix = { Text("秒", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = largeThreshold,
                        onValueChange = { largeThreshold = it },
                        label = { Text("大单探测阈值") },
                        suffix = { Text("手", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 播报选项卡片 ----
            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle("播报内容")

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            CompactCheck("现价", speakPrice, controlsEnabled) { speakPrice = it }
                            CompactCheck("涨幅", speakPct, controlsEnabled) { speakPct = it }
                            CompactCheck("涨速", speakSpeed, controlsEnabled) { speakSpeed = it }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            CompactCheck("成交额", speakAmount, controlsEnabled) { speakAmount = it }
                            CompactCheck("量比", speakVolRatio, controlsEnabled) { speakVolRatio = it }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            CompactCheck("现手", speakCurrentHand, controlsEnabled) { speakCurrentHand = it }
                            CompactCheck("盘口大单", speakLargeOrders, controlsEnabled) { speakLargeOrders = it }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 主按钮 ----
            Button(
                onClick = {
                    if (uiState.isRunning) {
                        StockMonitorService.stop(context)
                    } else {
                        if (stockCode.isBlank()) return@Button
                        configManager.save(
                            AppConfig(
                                stockCode = stockCode.trim(),
                                speakInterval = speakInterval.toIntOrNull() ?: 15,
                                largeOrderThreshold = largeThreshold.toIntOrNull() ?: 500,
                                speakPrice = speakPrice,
                                speakPct = speakPct,
                                speakSpeed = speakSpeed,
                                speakAmount = speakAmount,
                                speakVolRatio = speakVolRatio,
                                speakCurrentHand = speakCurrentHand,
                                speakLargeOrders = speakLargeOrders
                            )
                        )
                        StockMonitorService.start(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRunning) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (uiState.isRunning) "⏹ 停止盯盘" else "▶ 开始自动盯盘",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 行情卡片 ----
            if (uiState.isRunning && uiState.stockName.isNotEmpty()) {
                StockInfoCard(uiState, shape)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ---- 状态栏 ----
            StatusBar(uiState)
        }
    }
}

// ═══════════════════════════════════════════
// 子组件
// ═══════════════════════════════════════════

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp
    )
}

@Composable
private fun CompactCheck(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.outline
            )
        )
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StockInfoCard(uiState: ServiceUiState, shape: RoundedCornerShape) {
    val st = when {
        uiState.changePct > 0 -> "涨"
        uiState.changePct < 0 -> "跌"
        else -> "平"
    }
    val priceColor = when {
        uiState.changePct > 0 -> AppColors.priceUp
        uiState.changePct < 0 -> AppColors.priceDown
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 股票名称 + 涨跌标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = uiState.stockName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (uiState.changePct > 0) AppColors.priceUp.copy(alpha = 0.12f)
                    else if (uiState.changePct < 0) AppColors.priceDown.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${st}${abs(uiState.changePct)}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = priceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 大号价格
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    color = priceColor,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = uiState.price.toString(),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = priceColor,
                    fontFamily = FontFamily.Default
                )
            }

            // 涨速
            if (abs(uiState.speed) > 0) {
                Text(
                    text = "涨速 ${uiState.speed}%",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 分隔线
            HorizontalDivider(color = AppColors.dividerColor, thickness = 1.dp)

            Spacer(modifier = Modifier.height(12.dp))

            // 数据行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DataChip("成交额", uiState.amount)
                DataChip("量比", uiState.volRatio.toString())
                DataChip("现手", uiState.currentHand.toString())
            }

            // 大单信息
            if (uiState.largeAsks.isNotEmpty() || uiState.largeBids.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.dividerColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.largeAsks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔻", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "压单  ${uiState.largeAsks.joinToString("  ")}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.priceDown
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (uiState.largeBids.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔺", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "托单  ${uiState.largeBids.joinToString("  ")}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.priceUp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DataChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun StatusBar(uiState: ServiceUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (uiState.isRunning) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        ) {
            Text(
                text = if (uiState.isRunning && uiState.lastSpeakTime.isNotEmpty()) {
                    "📢 上次播报 ${uiState.lastSpeakTime}"
                } else {
                    uiState.statusText
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (uiState.isRunning) Color(0xFF2E7D32) else Color(0xFFE65100),
                textAlign = TextAlign.Center
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary
)
