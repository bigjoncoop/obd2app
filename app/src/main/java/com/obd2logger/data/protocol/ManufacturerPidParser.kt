package com.obd2logger.data.protocol

import com.obd2logger.domain.model.ManufacturerPidDefinition

/**
 * Pure-Kotlin JSON parser and READ-ONLY security validator for Manufacturer PID databases.
 * Rejects malformed JSON, unsafe formulas, invalid CAN headers, and any non-read OBD/UDS services.
 */
object ManufacturerPidParser {

    /**
     * Strictly permitted READ-ONLY OBD/UDS services:
     * - 01: SAE J1979 Current Data
     * - 09: SAE J1979 Vehicle Info
     * - 1A: KWP2000 Read ECU Identification
     * - 21: Toyota/KWP Read Data By Local Identifier
     * - 22: UDS Read Data By Identifier
     */
    private val ALLOWED_READ_SERVICES = setOf("01", "09", "1A", "21", "22")
    private val HEADER_REGEX = Regex("^[0-9A-Fa-f]{3}$|^[0-9A-Fa-f]{8}$")
    private val HEX_COMMAND_REGEX = Regex("^[0-9A-Fa-f]{4,12}$")

    fun parseJson(jsonText: String, isUserImported: Boolean = false): Result<List<ManufacturerPidDefinition>> {
        return runCatching {
            val root = SimpleJsonReader(jsonText).parseValue()
            val array: List<Any?> = when (root) {
                is List<*> -> root
                is Map<*, *> -> (root["pids"] as? List<*>)
                    ?: throw IllegalArgumentException("JSON object must contain a 'pids' array")
                else -> throw IllegalArgumentException("Root JSON must be an array or object with 'pids'")
            }

            val parsed = mutableListOf<ManufacturerPidDefinition>()
            val seenIds = mutableSetOf<String>()

            for ((idx, item) in array.withIndex()) {
                val obj = item as? Map<*, *>
                    ?: throw IllegalArgumentException("Entry #$idx is not a JSON object")
                val def = parseEntry(obj, idx, isUserImported)
                if (!seenIds.add(def.id)) {
                    throw IllegalArgumentException("Duplicate PID id '${def.id}' at entry #$idx")
                }
                parsed.add(def)
            }
            parsed
        }
    }

    private fun parseEntry(
        obj: Map<*, *>,
        idx: Int,
        isUserImported: Boolean
    ): ManufacturerPidDefinition {
        fun reqStr(key: String): String {
            val v = obj[key] as? String
            if (v.isNullOrBlank()) {
                throw IllegalArgumentException("Entry #$idx missing required string field '$key'")
            }
            return v.trim()
        }

        fun optStr(key: String): String? = (obj[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        fun optStrList(key: String): List<String> {
            val raw = obj[key] ?: return emptyList()
            val list = raw as? List<*>
                ?: throw IllegalArgumentException("Entry #$idx field '$key' must be an array of strings")
            return list.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        }

        fun optNum(key: String): Double? {
            val raw = obj[key] ?: return null
            return (raw as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Entry #$idx field '$key' must be a number")
        }

        val id = reqStr("id")
        val name = reqStr("name")
        val moduleName = reqStr("moduleName")
        val requestHeader = reqStr("requestHeader").replace(" ", "").uppercase()
        if (!HEADER_REGEX.matches(requestHeader)) {
            throw IllegalArgumentException("Entry '$id' has invalid CAN header '$requestHeader'")
        }

        val receiveAddress = optStr("receiveAddress")?.replace(" ", "")?.uppercase()?.also {
            if (!HEADER_REGEX.matches(it)) {
                throw IllegalArgumentException("Entry '$id' has invalid receiveAddress '$it'")
            }
        }
        val flowControlHeader = optStr("flowControlHeader")?.replace(" ", "")?.uppercase()?.also {
            if (!HEADER_REGEX.matches(it)) {
                throw IllegalArgumentException("Entry '$id' has invalid flowControlHeader '$it'")
            }
        }
        val flowControlData = optStr("flowControlData")?.replace(" ", "")?.uppercase()?.also {
            if (!Regex("^[0-9A-Fa-f]{2,16}$").matches(it) || it.length % 2 != 0) {
                throw IllegalArgumentException("Entry '$id' has invalid flowControlData '$it'")
            }
        }
        val flowControlMode = optStr("flowControlMode")?.uppercase()?.also {
            if (it !in setOf("0", "1", "2")) {
                throw IllegalArgumentException("Entry '$id' has invalid flowControlMode '$it'")
            }
        }

        val command = reqStr("command").replace(" ", "").uppercase()
        if (!HEX_COMMAND_REGEX.matches(command) || command.length % 2 != 0) {
            throw IllegalArgumentException("Entry '$id' has invalid hex command '$command'")
        }
        val servicePrefix = command.substring(0, 2)
        if (servicePrefix !in ALLOWED_READ_SERVICES) {
            throw SecurityException(
                "Entry '$id' uses disallowed non-read service 0x$servicePrefix. Only read-only services ($ALLOWED_READ_SERVICES) are permitted."
            )
        }

        val expectedBytes = (obj["expectedBytes"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Entry '$id' missing integer 'expectedBytes'")
        if (expectedBytes !in 1..32) {
            throw IllegalArgumentException("Entry '$id' expectedBytes must be 1..32")
        }

        val formula = reqStr("formula")
        SafeFormulaEvaluator.compile(formula).getOrElse { err ->
            throw IllegalArgumentException("Entry '$id' has unsafe or invalid formula '$formula': ${err.message}", err)
        }

        val unit = (obj["unit"] as? String)?.trim()
            ?: throw IllegalArgumentException("Entry '$id' missing 'unit'")
        val category = reqStr("category")
        val source = optStr("source") ?: "Custom"
        val decimals = (obj["decimals"] as? Number)?.toInt()?.coerceIn(0, 4) ?: 1

        return ManufacturerPidDefinition(
            id = id,
            name = name,
            makes = optStrList("makes"),
            models = optStrList("models"),
            years = optStr("years"),
            moduleName = moduleName,
            requestHeader = requestHeader,
            receiveAddress = receiveAddress,
            flowControlHeader = flowControlHeader,
            flowControlData = flowControlData,
            flowControlMode = flowControlMode,
            command = command,
            expectedBytes = expectedBytes,
            formula = formula,
            unit = unit,
            min = optNum("min"),
            max = optNum("max"),
            category = category,
            source = source,
            decimals = decimals,
            isUserImported = isUserImported
        )
    }

    /**
     * Minimal RFC 8259 compliant recursive JSON parser with zero Android runtime dependencies.
     */
    private class SimpleJsonReader(private val input: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of JSON")
            val value = when (val ch = input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char '$ch' at $pos")
            }
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (pos < input.length && input[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= input.length || input[pos] != '"') {
                    throw IllegalArgumentException("Expected object key string at $pos")
                }
                val key = parseString()
                skipWs()
                if (pos >= input.length || input[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' after key '$key' at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos >= input.length) throw IllegalArgumentException("Unterminated JSON object")
                when (input[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWs()
            if (pos < input.length && input[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos >= input.length) throw IllegalArgumentException("Unterminated JSON array")
                when (input[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = input[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) throw IllegalArgumentException("Invalid \\u escape")
                                val hex = input.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) throw IllegalArgumentException("Unescaped control char in string")
                        sb.append(ch)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseBoolean(): Boolean {
            return if (input.startsWith("true", pos)) {
                pos += 4
                true
            } else if (input.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (input.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (input[pos] == '-') pos++
            if (pos >= input.length || !input[pos].isDigit()) {
                throw IllegalArgumentException("Invalid number at $start")
            }
            while (pos < input.length && input[pos].isDigit()) pos++
            var isFloat = false
            if (pos < input.length && input[pos] == '.') {
                isFloat = true
                pos++
                if (pos >= input.length || !input[pos].isDigit()) {
                    throw IllegalArgumentException("Invalid fraction at $pos")
                }
                while (pos < input.length && input[pos].isDigit()) pos++
            }
            if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
                if (pos >= input.length || !input[pos].isDigit()) {
                    throw IllegalArgumentException("Invalid exponent at $pos")
                }
                while (pos < input.length && input[pos].isDigit()) pos++
            }
            val token = input.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}
