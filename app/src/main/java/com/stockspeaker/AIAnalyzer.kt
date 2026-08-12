package com.stockspeaker

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AiConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val apiUrl: String = "https://token.sensenova.cn/v1/chat/completions",
    val model: String = "sensenova-6.7-flash-lite",
    val summaryInterval: Int = 5,
    val provider: String = "sensenova"
)

/** SenseNova 只选择本地证据槽位，不生成自由股评。 */
class AIAnalyzer(
    private val configProvider: () -> AiConfig,
    private val onLog: (String) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile private var activeCall: Call? = null

    fun generateSummary(slots: List<AiEvidenceSlot>, callback: (String?) -> Unit) {
        val config = configProvider()
        if (!config.enabled || config.apiKey.isBlank() || slots.isEmpty()) {
            callback(null)
            return
        }
        val frozen = slots.toList()
        executor.execute {
            val result = try {
                val body = JSONObject().apply {
                    put("model", config.model)
                    put("temperature", 0)
                    put("max_tokens", 512)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().put("role", "system").put(
                            "content", "你是证据选择器，只能返回要求的JSON，不得输出建议、解释或额外文字。"
                        ))
                        put(JSONObject().put("role", "user").put("content", AiEvidenceSlots.prompt(frozen)))
                    })
                }.toString()
                val request = Request.Builder()
                    .url(config.apiUrl)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        onLog("AI辅助：HTTP ${response.code}")
                        null
                    } else {
                        val content = response.body?.string()?.let(::extractContent)
                        AiEvidenceSlots.validateAndFormat(content.orEmpty(), frozen)
                    }
                }
            } catch (e: Exception) {
                onLog("AI辅助：${e.message?.take(40) ?: "请求失败"}")
                null
            } finally {
                activeCall = null
            }
            callback(result)
        }
    }

    fun shutdown() {
        activeCall?.cancel()
        executor.shutdownNow()
    }

    private fun extractContent(body: String): String? = try {
        JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
