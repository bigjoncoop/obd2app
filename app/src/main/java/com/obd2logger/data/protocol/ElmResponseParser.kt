package com.obd2logger.data.protocol

import com.obd2logger.domain.model.PidDefinition

/**
 * Known ELM327 error categories returned by genuine and clone adapters.
 */
enum class ElmErrorType(val displayMessage: String) {
    NO_DATA("NO DATA"),
    UNKNOWN_COMMAND("? (Unknown Command)"),
    UNABLE_TO_CONNECT("UNABLE TO CONNECT"),
    BUS_BUSY("BUS BUSY"),
    CAN_ERROR("CAN ERROR"),
    STOPPED("STOPPED"),
    BUFFER_FULL("BUFFER FULL"),
    BUS_INIT_ERROR("BUS INIT: ERROR"),
    OTHER_ERROR("ELM327 Protocol Error")
}

/**
 * Result of parsing and validating an OBD2 response from an ELM327 adapter.
 */
sealed class ElmParseResult {
    /**
     * Validated OBD response that matched (0x40 + service) and expectedPid.
     *
     * @param service The OBD service mode (e.g. 0x01, 0x03).
     * @param pid The PID echoed back (null for Mode 03/07/0A which have no PID byte).
     * @param dataBytes The primary (or merged bitmask) payload bytes AFTER the service (+ PID) header.
     * @param ecuPayloads All individual valid ECU payloads (after service + PID header) when multiple ECUs respond.
     * @param rawCleaned Cleaned representation of the response.
     */
    data class Success(
        val service: Int,
        val pid: Int?,
        val dataBytes: ByteArray,
        val ecuPayloads: List<ByteArray>,
        val rawCleaned: String
    ) : ElmParseResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            if (service != other.service) return false
            if (pid != other.pid) return false
            if (!dataBytes.contentEquals(other.dataBytes)) return false
            if (ecuPayloads.size != other.ecuPayloads.size) return false
            for (i in ecuPayloads.indices) {
                if (!ecuPayloads[i].contentEquals(other.ecuPayloads[i])) return false
            }
            return rawCleaned == other.rawCleaned
        }

        override fun hashCode(): Int {
            var result = service
            result = 31 * result + (pid ?: 0)
            result = 31 * result + dataBytes.contentHashCode()
            result = 31 * result + rawCleaned.hashCode()
            return result
        }
    }

    /**
     * Adapter returned a recognized error token (NO DATA, UNABLE TO CONNECT, BUS INIT: ...ERROR, etc.).
     */
    data class Error(
        val errorType: ElmErrorType,
        val rawResponse: String
    ) : ElmParseResult()

    /**
     * Response did not contain a valid (0x40 + service) + PID frame.
     */
    data class InvalidResponse(
        val reason: String,
        val rawResponse: String
    ) : ElmParseResult()
}

/**
 * Result of parsing and validating a multi-PID Mode 01 response (e.g. `01 0C 0D 11` -> `41 0C ... 0D ... 11 ...`).
 */
sealed class MultiPidParseResult {
    data class Success(
        val pidPayloads: Map<Int, ByteArray>,
        val rawCleaned: String
    ) : MultiPidParseResult()

    data class Error(
        val errorType: ElmErrorType,
        val rawResponse: String
    ) : MultiPidParseResult()

    data class InvalidResponse(
        val reason: String,
        val rawResponse: String
    ) : MultiPidParseResult()
}

/**
 * Pure Kotlin parser and validator for ELM327 AT and OBD2 responses.
 * Handles clones, echoes, SEARCHING..., multi-ECU responses, ISO-TP multi-frame (0:, 1:, 2:...),
 * and all ELM327 error strings.
 */
object ElmResponseParser {

    private val ISO_TP_LINE_REGEX = Regex("^([0-9A-Fa-f]):([0-9A-Fa-f]+)$")
    private val HEX_ONLY_REGEX = Regex("^[0-9A-Fa-f]+$")
    private val ELM_VERSION_REGEX = Regex("(?i)(ELM327\\s*v?[0-9]+\\.[0-9a-zA-Z.]+|ELM327)")
    private val BITMASK_PIDS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    /**
     * Cleans raw bytes/text from the adapter into normalized lines:
     * - Strips null bytes (\u0000), '>' prompt characters
     * - Splits by \r and \n
     * - Strips command echo if present
     * - Filters out "SEARCHING..." and "BUS INIT: OK" / "BUS INIT: ...OK" progress lines
     */
    fun cleanLines(rawResponse: String, sentCommand: String? = null): List<String> {
        val normalizedCommand = sentCommand
            ?.replace(" ", "")
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?.uppercase()

        val withoutNullsAndPrompt = rawResponse
            .replace("\u0000", "")
            .replace(">", "\n")

        val rawLines = withoutNullsAndPrompt
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val cleaned = mutableListOf<String>()
        var echoStripped = false

        for (line in rawLines) {
            val upper = line.uppercase()
            val noSpaces = upper.replace(" ", "")

            // Strip echoed command on the first matching line
            if (!echoStripped && normalizedCommand != null && normalizedCommand.isNotEmpty()) {
                if (noSpaces == normalizedCommand) {
                    echoStripped = true
                    continue
                }
                // Some clones prepend echo directly onto the same line before CR if linefeeds are weird
                if (noSpaces.startsWith(normalizedCommand) && noSpaces.length > normalizedCommand.length) {
                    val remainder = line.trim().let {
                        // Strip leading command characters ignoring spaces
                        stripLeadingEcho(it, normalizedCommand)
                    }.trim()
                    echoStripped = true
                    if (remainder.isNotEmpty()) {
                        processLineAfterEcho(remainder, cleaned)
                    }
                    continue
                }
            }

            processLineAfterEcho(line, cleaned)
        }

        return cleaned
    }

    private fun stripLeadingEcho(line: String, normalizedCommand: String): String {
        var cmdIdx = 0
        var lineIdx = 0
        while (lineIdx < line.length && cmdIdx < normalizedCommand.length) {
            val ch = line[lineIdx]
            if (ch == ' ') {
                lineIdx++
                continue
            }
            if (ch.uppercaseChar() == normalizedCommand[cmdIdx]) {
                cmdIdx++
                lineIdx++
            } else {
                break
            }
        }
        return if (cmdIdx == normalizedCommand.length) line.substring(lineIdx) else line
    }

    private fun processLineAfterEcho(line: String, out: MutableList<String>) {
        var working = line.trim()
        val upper = working.uppercase()

        // Strip "SEARCHING..." prefix or full line
        if (upper == "SEARCHING..." || upper == "SEARCHING") {
            return
        }
        if (upper.startsWith("SEARCHING...")) {
            working = working.substring("SEARCHING...".length).trim()
            if (working.isEmpty()) return
        }

        // Ignore positive BUS INIT progress lines like "BUS INIT: OK" or "BUS INIT: ...OK"
        val upperWorking = working.uppercase()
        if (upperWorking.startsWith("BUS INIT") && upperWorking.endsWith("OK") && !upperWorking.contains("ERROR")) {
            return
        }

        out.add(working)
    }

    /**
     * Extracts the ELM327 version string from an ATZ or ATI response.
     * Returns null if "ELM327" was not present in the response.
     */
    fun extractElmVersion(rawResponse: String): String? {
        val cleaned = rawResponse.replace("\u0000", "").replace(">", " ")
        val match = ELM_VERSION_REGEX.find(cleaned) ?: return null
        return match.value.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Checks whether a single line or entire cleaned response represents a known ELM327 error.
     */
    fun detectError(lines: List<String>): ElmErrorType? {
        for (line in lines) {
            val upper = line.uppercase().trim()
            val compact = upper.replace(" ", "")
            when {
                upper == "?" || compact == "?" -> return ElmErrorType.UNKNOWN_COMMAND
                upper.contains("UNABLE TO CONNECT") || compact.contains("UNABLETOCONNECT") ->
                    return ElmErrorType.UNABLE_TO_CONNECT
                upper.contains("BUS INIT") && upper.contains("ERROR") ->
                    return ElmErrorType.BUS_INIT_ERROR
                upper.contains("NO DATA") || compact == "NODATA" ->
                    return ElmErrorType.NO_DATA
                upper.contains("BUS BUSY") || compact.contains("BUSBUSY") ->
                    return ElmErrorType.BUS_BUSY
                upper.contains("CAN ERROR") || compact.contains("CANERROR") ->
                    return ElmErrorType.CAN_ERROR
                upper.contains("BUFFER FULL") || compact.contains("BUFFERFULL") ->
                    return ElmErrorType.BUFFER_FULL
                upper.contains("STOPPED") ->
                    return ElmErrorType.STOPPED
                upper.contains("DATA ERROR") || upper.contains("FB ERROR") ||
                    upper.contains("ACT ALERT") || upper.contains("LV RESET") ||
                    upper.endsWith("ERROR") ->
                    return ElmErrorType.OTHER_ERROR
            }
        }
        return null
    }

    /**
     * Parses and validates an OBD2 response for the given [expectedService] (e.g., 0x01, 0x03, 0x07, 0x09, 0x0A)
     * and optional [expectedPid] (e.g., 0x00, 0x0C for Service 01; null for Service 03/07/0A).
     *
     * Every response MUST begin with (0x40 + expectedService) and [expectedPid] (if non-null)
     * before it is accepted as valid data.
     */
    fun parseObdResponse(
        rawResponse: String,
        expectedService: Int,
        expectedPid: Int? = null,
        sentCommand: String? = null
    ): ElmParseResult {
        val lines = cleanLines(rawResponse, sentCommand)
        if (lines.isEmpty()) {
            return ElmParseResult.InvalidResponse("Empty response from adapter", rawResponse)
        }

        val expectedResponseService = (0x40 + (expectedService and 0x3F)) and 0xFF

        // 1. Check if this is an ISO-TP multi-frame response (lines starting with "0:", "1:", "2:", ...)
        val isoTpResult = tryParseIsoTpMultiFrame(
            lines = lines,
            expectedResponseService = expectedResponseService,
            expectedService = expectedService,
            expectedPid = expectedPid,
            rawResponse = rawResponse
        )
        if (isoTpResult != null) {
            return isoTpResult
        }

        // 2. Parse single-line or multi-ECU / multi-line responses
        val validEcuPayloads = mutableListOf<ByteArray>()
        for (line in lines) {
            val compactHex = extractHexLineWithoutHeaderIfPresent(
                line = line,
                expectedResponseService = expectedResponseService,
                expectedPid = expectedPid
            ) ?: continue

            val bytes = hexToBytes(compactHex) ?: continue
            val payload = validateAndExtractPayload(bytes, expectedResponseService, expectedPid)
            if (payload != null) {
                validEcuPayloads.add(payload)
            }
        }

        if (validEcuPayloads.isNotEmpty()) {
            val mergedData = if (
                expectedService == 0x01 &&
                expectedPid != null &&
                expectedPid in BITMASK_PIDS &&
                validEcuPayloads.all { it.size >= 4 }
            ) {
                // Bitwise-OR 4-byte supported PID bitmasks across all responding ECUs
                val combined = ByteArray(4)
                for (ecuBytes in validEcuPayloads) {
                    for (i in 0 until 4) {
                        combined[i] = (combined[i].toInt() or ecuBytes[i].toInt()).toByte()
                    }
                }
                combined
            } else if (expectedService in setOf(0x03, 0x07, 0x0A) && validEcuPayloads.size > 1) {
                // For DTC modes across multiple ECUs/frames, concatenate payloads
                val totalSize = validEcuPayloads.sumOf { it.size }
                val combined = ByteArray(totalSize)
                var offset = 0
                for (ecuBytes in validEcuPayloads) {
                    ecuBytes.copyInto(combined, destinationOffset = offset)
                    offset += ecuBytes.size
                }
                combined
            } else {
                validEcuPayloads.first()
            }

            return ElmParseResult.Success(
                service = expectedService,
                pid = expectedPid,
                dataBytes = mergedData,
                ecuPayloads = validEcuPayloads,
                rawCleaned = lines.joinToString("\n")
            )
        }

        // 3. No valid data frames found; check if an ELM327 error token was returned
        val detectedError = detectError(lines)
        if (detectedError != null) {
            return ElmParseResult.Error(detectedError, rawResponse)
        }

        return ElmParseResult.InvalidResponse(
            reason = "Response did not match expected header %02X%s".format(
                expectedResponseService,
                expectedPid?.let { " %02X".format(it) } ?: ""
            ),
            rawResponse = rawResponse
        )
    }

    /**
     * Parses and validates a multi-PID Mode 01 response (header `41` followed by `<PID><data>...`
     * for each PID in [requestedPids]).
     *
     * Every PID in [requestedPids] must be validated with its expected byte length; otherwise
     * [MultiPidParseResult.InvalidResponse] or [MultiPidParseResult.Error] is returned so the caller
     * can fall back to single-PID polling.
     */
    fun parseMultiPidMode01Response(
        rawResponse: String,
        requestedPids: List<PidDefinition>,
        sentCommand: String? = null
    ): MultiPidParseResult {
        if (requestedPids.isEmpty()) {
            return MultiPidParseResult.InvalidResponse("No PIDs requested", rawResponse)
        }
        val lines = cleanLines(rawResponse, sentCommand)
        if (lines.isEmpty()) {
            return MultiPidParseResult.InvalidResponse("Empty response from adapter", rawResponse)
        }

        val expectedById = requestedPids.associateBy { it.id and 0xFF }
        val candidates = mutableListOf<ByteArray>()

        // 1. Check ISO-TP multi-frame (e.g. 0: 41 0C ... / 1: ...)
        val isoTpResult = tryParseIsoTpMultiFrame(
            lines = lines,
            expectedResponseService = 0x41,
            expectedService = 0x01,
            expectedPid = null,
            rawResponse = rawResponse
        )
        if (isoTpResult != null) {
            candidates.add(isoTpResult.dataBytes)
        }

        // 2. Check single-line hex responses starting with 41 (or 7E8 <len> 41 ...)
        for (line in lines) {
            val compactHex = extractHexLineWithoutHeaderIfPresent(
                line = line,
                expectedResponseService = 0x41,
                expectedPid = null
            ) ?: continue
            val bytes = hexToBytes(compactHex) ?: continue
            val payload = validateAndExtractPayload(bytes, 0x41, null) ?: continue
            candidates.add(payload)
        }

        val mergedPayloads = linkedMapOf<Int, ByteArray>()
        for (payload in candidates) {
            val extracted = extractMultiPidPayloads(payload, expectedById)
            if (extracted != null) {
                mergedPayloads.putAll(extracted)
            }
        }

        if (mergedPayloads.size == expectedById.size) {
            return MultiPidParseResult.Success(
                pidPayloads = mergedPayloads,
                rawCleaned = lines.joinToString("\n")
            )
        }

        val detectedError = detectError(lines)
        if (detectedError != null) {
            return MultiPidParseResult.Error(detectedError, rawResponse)
        }

        return MultiPidParseResult.InvalidResponse(
            reason = "Multi-PID response missing or invalid 41 + PID frames (parsed ${mergedPayloads.size}/${expectedById.size})",
            rawResponse = rawResponse
        )
    }

    private fun extractMultiPidPayloads(
        payloadAfter41: ByteArray,
        expectedById: Map<Int, PidDefinition>
    ): Map<Int, ByteArray>? {
        val result = linkedMapOf<Int, ByteArray>()
        var cursor = 0
        while (cursor < payloadAfter41.size && result.size < expectedById.size) {
            val pidId = payloadAfter41[cursor].toInt() and 0xFF
            val def = expectedById[pidId] ?: return null
            val dataStart = cursor + 1
            val dataEnd = dataStart + def.expectedBytes
            if (dataEnd > payloadAfter41.size) return null
            result[pidId] = payloadAfter41.copyOfRange(dataStart, dataEnd)
            cursor = dataEnd
        }
        return if (result.size == expectedById.size) result else null
    }

    /**
     * Handles ISO-TP multi-frame responses where lines are prefixed with "0:", "1:", "2:", etc.
     * Supports optional 3-hex-digit byte length line (e.g. "014" or "00A") before frame 0.
     */
    private fun tryParseIsoTpMultiFrame(
        lines: List<String>,
        expectedResponseService: Int,
        expectedService: Int,
        expectedPid: Int?,
        rawResponse: String
    ): ElmParseResult.Success? {
        val compactLines = lines.map { it.replace(" ", "").uppercase() }
        val frameMatches = compactLines.mapNotNull { line ->
            ISO_TP_LINE_REGEX.matchEntire(line)?.let { match ->
                val frameIdx = match.groupValues[1].toInt(16)
                val hexData = match.groupValues[2]
                frameIdx to hexData
            }
        }

        if (frameMatches.isEmpty()) return null

        // Check if there is a 3-character hex length indicator line (e.g. "014" -> 20 bytes)
        val declaredTotalLength: Int? = compactLines.firstOrNull { line ->
            line.length == 3 && HEX_ONLY_REGEX.matches(line)
        }?.toIntOrNull(16)

        // Sort frames by frame index (preserving relative order for wrap-around if > 16 frames)
        val orderedFrames = frameMatches.sortedBy { it.first }
        val combinedHex = buildString {
            for ((_, hexChunk) in orderedFrames) {
                append(hexChunk)
            }
        }

        val allBytes = hexToBytes(combinedHex) ?: return null
        if (allBytes.isEmpty()) return null

        // Trim to declared ISO-TP length if valid and within bounds
        val headerSize = if (expectedPid != null) 2 else 1
        val trimmedBytes = if (
            declaredTotalLength != null &&
            declaredTotalLength >= headerSize &&
            declaredTotalLength <= allBytes.size
        ) {
            allBytes.copyOfRange(0, declaredTotalLength)
        } else {
            allBytes
        }

        // Locate and validate the expected (0x40 + service) [+ PID] header
        val payload = validateAndExtractPayload(trimmedBytes, expectedResponseService, expectedPid)
            ?: findAndExtractPayloadInMultiFrame(trimmedBytes, expectedResponseService, expectedPid)
            ?: return null

        return ElmParseResult.Success(
            service = expectedService,
            pid = expectedPid,
            dataBytes = payload,
            ecuPayloads = listOf(payload),
            rawCleaned = lines.joinToString("\n")
        )
    }

    /**
     * Validates that [bytes] begins with [expectedResponseService] and [expectedPid] (if provided),
     * returning the data payload bytes after the header.
     */
    private fun validateAndExtractPayload(
        bytes: ByteArray,
        expectedResponseService: Int,
        expectedPid: Int?
    ): ByteArray? {
        if (bytes.isEmpty()) return null
        val firstByte = bytes[0].toInt() and 0xFF
        if (firstByte != expectedResponseService) return null

        return if (expectedPid != null) {
            if (bytes.size < 2) return null
            val secondByte = bytes[1].toInt() and 0xFF
            if (secondByte != (expectedPid and 0xFF)) return null
            // Must have at least 1 data byte after 41 XX
            if (bytes.size < 3) return null
            bytes.copyOfRange(2, bytes.size)
        } else {
            if (bytes.size < 2) return null
            bytes.copyOfRange(1, bytes.size)
        }
    }

    /**
     * In some ISO-TP responses (e.g. Mode 09 VIN or multi-frame with PCI byte),
     * checks if the expected header starts at index 0 or index 1 (after a single-byte frame count).
     */
    private fun findAndExtractPayloadInMultiFrame(
        bytes: ByteArray,
        expectedResponseService: Int,
        expectedPid: Int?
    ): ByteArray? {
        for (offset in 0..minOf(2, bytes.size - 1)) {
            val slice = bytes.copyOfRange(offset, bytes.size)
            val extracted = validateAndExtractPayload(slice, expectedResponseService, expectedPid)
            if (extracted != null) return extracted
        }
        return null
    }

    /**
     * Strips spaces from a line and verifies it is valid hex.
     * Also handles adapters where ATH0 was ignored and a 3-digit CAN ID + 1-byte PCI length
     * precedes the response (e.g. "7E8 06 41 00 BE 3F A8 13").
     */
    private fun extractHexLineWithoutHeaderIfPresent(
        line: String,
        expectedResponseService: Int,
        expectedPid: Int?
    ): String? {
        val compact = line.replace(" ", "").uppercase()
        if (compact.length < 2 || !HEX_ONLY_REGEX.matches(compact)) return null

        val expectedPrefix = if (expectedPid != null) {
            "%02X%02X".format(expectedResponseService, expectedPid and 0xFF)
        } else {
            "%02X".format(expectedResponseService)
        }

        if (compact.startsWith(expectedPrefix)) {
            // Ensure even number of hex chars
            return if (compact.length % 2 == 0) compact else null
        }

        // Handle 11-bit CAN header (3 hex chars e.g. 7E8..7EF) + 1 byte length (2 hex chars) -> 5 hex chars prefix
        if (compact.length >= 5 + expectedPrefix.length && compact.startsWith("7E")) {
            val afterCanHeader = compact.substring(5)
            if (afterCanHeader.startsWith(expectedPrefix) && afterCanHeader.length % 2 == 0) {
                return afterCanHeader
            }
            // Or 3 hex chars CAN header without length byte -> 3 hex chars prefix
            val afterCanIdOnly = compact.substring(3)
            if (afterCanIdOnly.startsWith(expectedPrefix) && afterCanIdOnly.length % 2 == 0) {
                return afterCanIdOnly
            }
        }

        return null
    }

    /**
     * Decodes a 4-byte supported PID bitmask (from 0100, 0120, 0140, 0160, 0180, 01A0)
     * into the set of supported PID integers (1-based offset from [basePid]).
     *
     * Bit 7 (MSB) of byte 0 corresponds to [basePid] + 1.
     * Bit 0 (LSB) of byte 3 corresponds to [basePid] + 32 (0x20).
     */
    fun decodeSupportedPidsBitmask(basePid: Int, maskBytes: ByteArray): Set<Int> {
        if (maskBytes.size < 4) return emptySet()
        val supported = linkedSetOf<Int>()
        for (byteIndex in 0 until 4) {
            val byteVal = maskBytes[byteIndex].toInt() and 0xFF
            for (bitIndex in 0 until 8) {
                val mask = 1 shl (7 - bitIndex)
                if ((byteVal and mask) != 0) {
                    val pid = basePid + (byteIndex * 8) + bitIndex + 1
                    supported.add(pid)
                }
            }
        }
        return supported
    }

    /**
     * Converts an even-length hexadecimal string to a [ByteArray], or null if invalid.
     */
    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(" ", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
