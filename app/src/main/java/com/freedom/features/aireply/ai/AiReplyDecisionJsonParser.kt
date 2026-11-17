package com.freedom.features.aireply.ai

/** Strict JSON Schema and parser shared by the decision engine and provider request contract. */
internal object AiReplyDecisionJsonParser {
    fun schema(maxDraftChars: Int): String = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "action": {
              "type": "string",
              "enum": ["REPLY", "DO_NOT_REPLY", "WAIT", "HUMAN_REVIEW"]
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

    fun parse(raw: String, maxDraftChars: Int): AiReplyDecision? = runCatching {
        val values = StrictJsonStringObjectReader(raw).read()
        require(values.keys == REQUIRED_KEYS || values.keys == REQUIRED_KEYS + "reasonDetail")
        val reasonDetail = values["reasonDetail"]
        if ("reasonDetail" in values) require(!reasonDetail.isNullOrBlank() && reasonDetail.length <= 1000)

        val actionValue = values["action"] ?: error("action missing")
        val reason = values["reason"] ?: error("reason missing")
        require(reason.isNotBlank() && reason.length <= 120 && REASON_PATTERN.matches(reason))
        val action = AiReplyAction.entries.firstOrNull { it.name == actionValue }
            ?: error("action invalid")
        val draft = values["draft"]

        when (action) {
            AiReplyAction.REPLY -> {
                require(!draft.isNullOrBlank())
                require(draft.length <= maxDraftChars)
                require(draft == draft.trim())
                AiReplyDecision(action, reason, draft, reasonDetail = reasonDetail)
            }
            AiReplyAction.DO_NOT_REPLY, AiReplyAction.WAIT, AiReplyAction.HUMAN_REVIEW -> {
                require(draft == null)
                AiReplyDecision(action, reason, reasonDetail = reasonDetail)
            }
        }
    }.getOrNull()

    private val REQUIRED_KEYS = setOf("action", "reason", "draft")
    private val REASON_PATTERN = Regex("[A-Z0-9_]{1,120}")

    /** Accepts only a root object whose values are JSON strings or null; rejects duplicates/trailing data. */
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
