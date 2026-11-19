package com.obd2logger.domain.model

enum class Mode06Phase(val label: String) {
    START("Start"),
    DURING("During"),
    END("End")
}

enum class Mode06ResultStatus(val label: String) {
    PASS("PASS"),
    FAIL("FAIL"),
    UNKNOWN("UNKNOWN")
}

/**
 * Decoded single SAE J1979 Mode 06 on-board monitor test record.
 */
data class Mode06TestResult(
    val timestampMs: Long,
    val phase: Mode06Phase,
    val midHex: String,
    val midName: String,
    val tidHex: String,
    val tidName: String,
    val uasidHex: String,
    val unit: String,
    val testValue: String,
    val minLimit: String,
    val maxLimit: String,
    val result: Mode06ResultStatus,
    val rawHex: String
)
