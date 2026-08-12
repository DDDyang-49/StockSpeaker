package com.stockspeaker

data class AiEvidenceSlot(
    val evidenceId: String,
    val phraseId: String,
    val fact: String,
    val speech: String
)

object AiEvidenceSlots {
    fun prompt(slots: List<AiEvidenceSlot>): String = buildString {
        append("只从下列证据中选择最多2项，不得新增事实。只返回JSON：")
        append("{\"usedEvidenceIds\":[\"id\"],\"phraseIds\":[\"phrase_id\"]}。证据：")
        slots.forEach { append("${it.evidenceId}|${it.phraseId}|${it.fact}；") }
    }

    fun validateAndFormat(raw: String, slots: List<AiEvidenceSlot>): String? {
        val json = raw.substringAfter('{', "").let { if (it.isBlank()) "" else "{$it" }
            .substringBeforeLast('}', "").let { if (it.isBlank()) "" else "$it}" }
        if (!json.startsWith("{") || !json.endsWith("}")) return null
        val used = jsonStringArray(json, "usedEvidenceIds") ?: return null
        val phrases = jsonStringArray(json, "phraseIds") ?: return null
        if (used.isEmpty() || used.size != phrases.size || used.size > 2) return null
        val byId = slots.associateBy { it.evidenceId }
        val selected = used.indices.map { index ->
            val slot = byId[used[index]] ?: return null
            if (slot.phraseId != phrases[index]) return null
            slot.speech
        }
        return selected.distinct().joinToString("，").takeIf { it.isNotBlank() }
    }

    private fun jsonStringArray(raw: String, key: String): List<String>? {
        val body = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1) ?: return null
        if (body.isBlank()) return emptyList()
        val tokens = Regex("\\\"((?:\\\\.|[^\\\"])*)\\\"").findAll(body).map { it.groupValues[1] }.toList()
        val residue = body.replace(Regex("\\\"((?:\\\\.|[^\\\"])*)\\\""), "").replace(",", "").trim()
        return tokens.takeIf { residue.isEmpty() }
    }
}
