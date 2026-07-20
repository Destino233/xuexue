@file:Suppress("ComplexCondition", "LongParameterList", "MaxLineLength")

package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonElement

internal data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

internal data class AwsSignedRequest(
    val url: String,
    val headers: Map<String, String>,
)

/** Platform-neutral cryptographic primitives used by the Bedrock SigV4 adapter. */
internal expect fun sha256Hex(value: ByteArray): String

internal expect fun hmacSha256(
    key: ByteArray,
    value: ByteArray,
): ByteArray

internal expect fun currentAwsAmzDate(): String

internal fun signAwsRequest(
    method: String,
    url: String,
    canonicalUri: String,
    headers: Map<String, String>,
    body: ByteArray,
    service: String,
    region: String,
    credentials: AwsCredentials,
    amzDate: String,
): AwsSignedRequest {
    val normalizedHeaders =
        headers
            .mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> value.trim().replace(Regex("\\s+"), " ") }
            .toSortedMap()
    val payloadHash = sha256Hex(body)
    val signedHeaders = normalizedHeaders.keys.joinToString(";")
    val canonicalHeaders =
        normalizedHeaders.entries.joinToString(separator = "") { (key, value) ->
            "$key:$value\n"
        }
    val canonicalRequest =
        listOf(
            method,
            canonicalUri,
            "",
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
    val date = amzDate.take(8)
    val scope = "$date/$region/$service/aws4_request"
    val stringToSign =
        "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.encodeToByteArray())}"
    val dateKey = hmacSha256("AWS4${credentials.secretAccessKey}".encodeToByteArray(), date.encodeToByteArray())
    val regionKey = hmacSha256(dateKey, region.encodeToByteArray())
    val serviceKey = hmacSha256(regionKey, service.encodeToByteArray())
    val signingKey = hmacSha256(serviceKey, "aws4_request".encodeToByteArray())
    val signature = sha256Hex(hmacSha256(signingKey, stringToSign.encodeToByteArray()))
    val authorization =
        "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    return AwsSignedRequest(
        url = url,
        headers = normalizedHeaders + ("authorization" to authorization),
    )
}

internal fun awsEncodePathSegment(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        val code = byte.toInt() and 0xff
        if (
            code in 0x41..0x5a ||
            code in 0x61..0x7a ||
            code in 0x30..0x39 ||
            code == '-'.code ||
            code == '_'.code ||
            code == '.'.code ||
            code == '~'.code
        ) {
            code.toChar().toString()
        } else {
            "%${code.toString(16).uppercase().padStart(2, '0')}"
        }
    }

internal fun jsonBody(element: JsonElement): ByteArray = element.toString().encodeToByteArray()
