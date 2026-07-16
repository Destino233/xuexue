package dev.xuexue.pi.coding.session

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

fun assertValidSessionId(id: String) {
    require(SESSION_ID_PATTERN.matches(id)) {
        "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
            "and start and end with an alphanumeric character"
    }
}

internal fun createSessionId(): String {
    val bytes = Random.Default.nextBytes(UUID_BYTE_COUNT)
    val timestamp = Clock.System.now().toEpochMilliseconds()
    for (index in 0 until TIMESTAMP_BYTE_COUNT) {
        bytes[index] = (timestamp ushr (40 - index * 8)).toByte()
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return bytes.toUuidString()
}

internal fun currentIsoTimestamp(): String {
    val value = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toString()
    return if ('.' in value) value else value.removeSuffix("Z") + ".000Z"
}

internal fun generateEntryId(existingIds: Set<String>): String {
    repeat(MAX_SHORT_ID_ATTEMPTS) {
        val id = Random.Default.nextBytes(SHORT_ID_BYTE_COUNT).toHexString()
        if (id !in existingIds) return id
    }
    return Random.Default.nextBytes(UUID_BYTE_COUNT).toUuidString()
}

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        this@toHexString.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

private fun ByteArray.toUuidString(): String {
    val hex = toHexString()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

private const val MAX_SHORT_ID_ATTEMPTS = 100
private const val TIMESTAMP_BYTE_COUNT = 6
private const val SHORT_ID_BYTE_COUNT = 4
private const val UUID_BYTE_COUNT = 16
private const val HEX_DIGITS = "0123456789abcdef"
private val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")
