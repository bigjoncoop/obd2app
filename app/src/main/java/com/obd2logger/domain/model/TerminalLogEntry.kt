package com.obd2logger.domain.model

enum class LogEntryType {
    TX,
    RX,
    ERROR,
    INFO
}

data class TerminalLogEntry(
    val id: Long,
    val timestampMs: Long,
    val formattedTime: String,
    val type: LogEntryType,
    val message: String,
    val isHeartbeat: Boolean = false
) {
    fun toPlainTextLine(): String = "[$formattedTime] [${type.name}] $message"
}
