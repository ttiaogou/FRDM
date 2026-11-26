package com.freedom.features.aireply.ai

/** 决策引擎与供应商请求契约共用的严格 JSON Schema 和解析器。 */
/** Fixed parser failure codes contain no untrusted model text. */
enum class AiReplyOutputInvalidReason {
    JSON_SYNTAX_INVALID, JSON_REQUIRED_FIELD_MISSING, JSON_SCHEMA_MISMATCH,
    JSON_REASON_DETAIL_INVALID, JSON_REASON_INVALID, JSON_ACTION_INVALID,
    JSON_DRAFT_EMPTY, JSON_DRAFT_TOO_LONG, JSON_DRAFT_INVALID
}

internal object AiReplyDecisionJsonParser {
    fun simpleSchema(maxDraftChars: Int): String = """
        {"type":"object","additionalProperties":false,"properties":{
          "action":{"type":"string","enum":["REPLY","DO_NOT_REPLY","WAIT"]},
          "draft":{"type":["string","null"],"maxLength":$maxDraftChars}
        },"required":["action","draft"]}
    """.trimIndent()
    fun schema(maxDraftChars: Int): String = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "action": {
              "type": "string",
              "enum": ["REPLY", "DO_NOT_REPLY", "WAIT"]
            },
            "reason": {
              "type": "string",
              "minLength": 1,
              "maxLength": 120,
              "pattern": "^[A-Z0-9_]+$"
            },
            "reasonDetail": {
              "type": "string",
              "minLength": 1,
              "maxLength": 1000
            },
            "draft": {
              "type": ["string", "null"],
              "maxLength": $maxDraftChars
            }
          },
          "required": ["action", "reason", "reasonDetail", "draft"]
        }
    """.trimIndent()

    /** Safe diagnostic result; never stores raw response or exception messages. */
    data class ParseOutcome(
        val decision: AiReplyDecision? = null,
        val invalidReason: AiReplyOutputInvalidReason? = null
    )

    private class InvalidDecision(val code: AiReplyOutputInvalidReason) :
        IllegalArgumentException("Invalid model decision protocol")

    private fun invalid(code: AiReplyOutputInvalidReason): Nothing = throw InvalidDecision(code)

    fun parse(raw: String, maxDraftChars: Int): AiReplyDecision? =
        parseWithDiagnostics(raw, maxDraftChars).decision

    /** Exactly the previous acceptance checks, now with safe rejection subcodes. */
    fun parseWithDiagnostics(raw: String, maxDraftChars: Int, allowSimple: Boolean = false): ParseOutcome = runCatching {
        val normalized = normalizeJsonCandidate(raw)
        val values = try {
            StrictJsonStringObjectReader(normalized).read()
        } catch (_: Exception) {
            invalid(AiReplyOutputInvalidReason.JSON_SYNTAX_INVALID)
        }
        val acceptedKeys = if (allowSimple) setOf("action", "draft") else REQUIRED_KEYS
        if (values.keys != acceptedKeys && values.keys != REQUIRED_KEYS && values.keys != REQUIRED_KEYS + "reasonDetail") {
            invalid(if (!values.keys.containsAll(REQUIRED_KEYS)) {
                AiReplyOutputInvalidReason.JSON_REQUIRED_FIELD_MISSING
            } else AiReplyOutputInvalidReason.JSON_SCHEMA_MISMATCH)
        }
        val reasonDetail = values["reasonDetail"]
        if ("reasonDetail" in values && (reasonDetail.isNullOrBlank() || reasonDetail.length > 1000)) {
            invalid(AiReplyOutputInvalidReason.JSON_REASON_DETAIL_INVALID)
        }

        val actionValue = values["action"]
            ?: invalid(AiReplyOutputInvalidReason.JSON_REQUIRED_FIELD_MISSING)
        val reason = values["reason"] ?: if (allowSimple) "SIMPLE_JSON" else
            invalid(AiReplyOutputInvalidReason.JSON_REQUIRED_FIELD_MISSING)
        if (reason.isBlank() || reason.length > 120 || !REASON_PATTERN.matches(reason)) {
            invalid(AiReplyOutputInvalidReason.JSON_REASON_INVALID)
        }
        val action = AiReplyAction.entries.firstOrNull { it.name == actionValue }
            ?: invalid(AiReplyOutputInvalidReason.JSON_ACTION_INVALID)
        val draft = values["draft"]

        val decision = when (action) {
            AiReplyAction.REPLY -> {
                if (draft.isNullOrBlank()) invalid(AiReplyOutputInvalidReason.JSON_DRAFT_EMPTY)
                if (draft.length > maxDraftChars) invalid(AiReplyOutputInvalidReason.JSON_DRAFT_TOO_LONG)
                if (draft != draft.trim()) invalid(AiReplyOutputInvalidReason.JSON_DRAFT_INVALID)
                AiReplyDecision(action, reason, draft, reasonDetail = reasonDetail)
            }
            AiReplyAction.DO_NOT_REPLY, AiReplyAction.WAIT -> {
                if (draft != null) invalid(AiReplyOutputInvalidReason.JSON_DRAFT_INVALID)
                AiReplyDecision(action, reason, reasonDetail = reasonDetail)
            }
        }
        ParseOutcome(decision = decision)
    }.getOrElse { failure ->
        ParseOutcome(invalidReason = (failure as? InvalidDecision)?.code
            ?: AiReplyOutputInvalidReason.JSON_SYNTAX_INVALID)
    }

    fun extractPlainTextDraft(raw: String, maxDraftChars: Int): String? {
        var candidate = raw.trim()
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            candidate = candidate.removePrefix("```").removeSuffix("```").trim()
            if (candidate.startsWith("json", ignoreCase = true)) candidate = candidate.substring(4).trim()
        }
        if (candidate.isBlank() || candidate.length > maxDraftChars) return null
        if (candidate.startsWith("{") && candidate.endsWith("}")) {
            parseWithDiagnostics(candidate, maxDraftChars).decision?.draft?.let { return it }
            return null
        }
        candidate = candidate.removePrefix("回复：").removePrefix("回复:")
            .removePrefix("draft:").removePrefix("Draft:").trim()
        if (candidate.isBlank() || candidate.startsWith("{") || candidate.contains("```")) return null
        return candidate.takeIf { it.length <= maxDraftChars }
    }

    private fun normalizeJsonCandidate(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
            if (text.startsWith("json", ignoreCase = true)) text = text.substring(4).trim()
        }
        val start = text.indexOf('{')
        if (start <= 0) return text
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return text
    }

    private val REQUIRED_KEYS = setOf("action", "reason", "draft")
    private val REASON_PATTERN = Regex("[A-Z0-9_]{1,120}")

    /** 仅接受值为 JSON 字符串或 null 的根对象；拒绝重复字段和末尾多余数据。 */
    private class StrictJsonStringObjectReader(private val source: String) {
        private var index = 0

        fun read(): Map<String, String?> {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val values = LinkedHashMap<String, String?>()
            if (peek() == '}') {
                index++
                skipWhitespace()
                require(index == source.length)
                return values
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = if (source.startsWith("null", index)) {
                    index += 4
                    null
                } else {
                    readString()
                }
                require(!values.containsKey(key)) { "duplicate property" }
                values[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        break
                    }
                    else -> error("invalid object separator")
                }
            }
            skipWhitespace()
            require(index == source.length) { "trailing JSON data" }
            return values
        }

        private fun readString(): String {
            expect('"')
            val output = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return output.toString()
                    char == '\\' -> {
                        require(index < source.length) { "unfinished escape" }
                        when (val escaped = source[index++]) {
                            '"' -> output.append('"')
                            '\\' -> output.append('\\')
                            '/' -> output.append('/')
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000C')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> output.append(readUnicodeEscape())
                            else -> error("invalid escape: $escaped")
                        }
                    }
                    char.code < 0x20 -> error("unescaped control character")
                    else -> output.append(char)
                }
            }
            error("unterminated JSON string")
        }

        private fun readUnicodeEscape(): Char {
            require(index + 4 <= source.length) { "unfinished unicode escape" }
            val value = source.substring(index, index + 4).toInt(16)
            index += 4
            return value.toChar()
        }

        private fun expect(expected: Char) {
            require(peek() == expected) { "expected $expected" }
            index++
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun skipWhitespace() {
            while (index < source.length && source[index] in WHITESPACE) index++
        }

        private companion object {
            val WHITESPACE = setOf(' ', '\n', '\r', '\t')
        }
    }
}
