package dev.xuexue.pi.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

@Serializable
data class DiagnosticErrorInfo(
    val name: String? = null,
    val message: String,
    val stack: String? = null,
    val code: JsonPrimitive? = null,
)

@Serializable
data class AssistantMessageDiagnostic(
    val type: String,
    val timestamp: Long,
    val error: DiagnosticErrorInfo? = null,
    val details: JsonObject? = null,
)

interface DiagnosticCodeCarrier {
    val diagnosticCode: JsonPrimitive?
}

fun formatThrownValue(value: Any?): String =
    when (value) {
        is Throwable -> value.message ?: value::class.simpleName.orEmpty()
        is String -> value
        else -> value.toString()
    }

fun extractDiagnosticError(error: Any?): DiagnosticErrorInfo {
    if (error !is Throwable) {
        return DiagnosticErrorInfo(
            name = "ThrownValue",
            message = formatThrownValue(error),
        )
    }

    return DiagnosticErrorInfo(
        name = error::class.simpleName,
        message = error.message ?: error::class.simpleName.orEmpty(),
        stack = error.stackTraceToString(),
        code = (error as? DiagnosticCodeCarrier)?.diagnosticCode,
    )
}

fun createAssistantMessageDiagnostic(
    type: String,
    error: Any?,
    details: JsonObject? = null,
    timestamp: Long = Clock.System.now().toEpochMilliseconds(),
): AssistantMessageDiagnostic =
    AssistantMessageDiagnostic(
        type = type,
        timestamp = timestamp,
        error = extractDiagnosticError(error),
        details = details,
    )

fun AssistantMessage.appendDiagnostic(
    diagnostic: AssistantMessageDiagnostic,
): AssistantMessage = copy(diagnostics = diagnostics.orEmpty() + diagnostic)
