package com.stockspeaker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.stockspeaker.ui.theme.StockSpeakerTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing needed */ }

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

        setContent {
            StockSpeakerTheme {
                StockSpeakerApp(configManager)
            }
        }
    }
}

@Composable
fun StockSpeakerApp(configManager: ConfigManager) {
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
                TextButton(onClick = { showTipsDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("摸鱼听盘", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showTipsDialog = true }) {
                    Text("?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val controlsEnabled = !uiState.isRunning

            // Stock code + interval row
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = stockCode,
                    onValueChange = { stockCode = it },
                    label = { Text("股票代码") },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = speakInterval,
                    onValueChange = { speakInterval = it },
                    label = { Text("播报间隔(秒)") },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Threshold
            OutlinedTextField(
                value = largeThreshold,
                onValueChange = { largeThreshold = it },
                label = { Text("大单探测阈值(手)") },
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("请勾选播报内容:", fontSize = 14.sp)

            // Checkboxes in compact rows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    CheckRow("现价", speakPrice, controlsEnabled) { speakPrice = it }
                    CheckRow("涨幅", speakPct, controlsEnabled) { speakPct = it }
                    CheckRow("涨速", speakSpeed, controlsEnabled) { speakSpeed = it }
                }
                Column(modifier = Modifier.weight(1f)) {
                    CheckRow("成交额", speakAmount, controlsEnabled) { speakAmount = it }
                    CheckRow("量比", speakVolRatio, controlsEnabled) { speakVolRatio = it }
                }
                Column(modifier = Modifier.weight(1f)) {
                    CheckRow("现手", speakCurrentHand, controlsEnabled) { speakCurrentHand = it }
                    CheckRow("盘口大单", speakLargeOrders, controlsEnabled) { speakLargeOrders = it }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Start/Stop button
            Button(
                onClick = {
                    if (uiState.isRunning) {
                        StockMonitorService.stop(context)
                    } else {
                        if (stockCode.isBlank()) return@Button
                        val config = AppConfig(
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
                        configManager.save(config)
                        StockMonitorService.start(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRunning) Color.Red else Color(0xFF1976D2)
                )
            ) {
                Text(
                    text = if (uiState.isRunning) "停止盯盘" else "开始自动盯盘",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status line
            Text(
                text = if (uiState.isRunning && uiState.lastSpeakTime.isNotEmpty()) {
                    "📢 上次播报: ${uiState.lastSpeakTime}"
                } else {
                    uiState.statusText
                },
                color = Color.Gray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Divider()

            Spacer(modifier = Modifier.height(8.dp))

            // Stock info
            if (uiState.isRunning && uiState.stockName.isNotEmpty()) {
                val st = when {
                    uiState.changePct > 0 -> "涨"
                    uiState.changePct < 0 -> "跌"
                    else -> "平"
                }
                val priceColor = when {
                    uiState.changePct > 0 -> Color.Red
                    uiState.changePct < 0 -> Color(0xFF008000)
                    else -> Color.Black
                }

                Text(
                    text = "【${uiState.stockName}】 现价: ${uiState.price} (${st}${abs(uiState.changePct)}%)  涨速: ${uiState.speed}%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = priceColor
                )
                Text(
                    text = "成交额: ${uiState.amount} | 量比: ${uiState.volRatio} | 现手: ${uiState.currentHand}",
                    fontSize = 14.sp,
                    color = Color(0xFF607D8B)
                )

                if (uiState.largeAsks.isNotEmpty()) {
                    Text(
                        text = "🔻压单: ${uiState.largeAsks.joinToString(" , ")}",
                        color = Color(0xFF008000),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (uiState.largeBids.isNotEmpty()) {
                    Text(
                        text = "🔺托单: ${uiState.largeBids.joinToString(" , ")}",
                        color = Color.Red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (!uiState.isRunning) {
                Text("准备就绪", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, fontSize = 14.sp)
    }
}
