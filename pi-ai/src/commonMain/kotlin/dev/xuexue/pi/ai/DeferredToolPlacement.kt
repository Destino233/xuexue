package dev.xuexue.pi.ai

/**
 * The two native deferred-tool protocols use the same transcript marker but
 * expose different wire shapes. Keep the placement decision provider-neutral.
 */
internal data class DeferredToolPlacement(
    val immediate: List<Tool>,
    val deferred: Map<String, Tool>,
)

private val identityToolName: (String) -> String = { it }

/** Split current tools into the prompt prefix and transcript-loaded tools. */
internal fun splitDeferredTools(
    context: Context,
    enabled: Boolean,
    normalizeName: (String) -> String = identityToolName,
): DeferredToolPlacement {
    val uniqueTools = linkedMapOf<String, Tool>()
    context.tools.orEmpty().forEach { tool ->
        uniqueTools[normalizeName(tool.name)] = tool
    }
    if (!enabled) return DeferredToolPlacement(uniqueTools.values.toList(), emptyMap())

    val deferredNames = mutableSetOf<String>()
    val usedNames = mutableSetOf<String>()
    context.messages.forEach { message ->
        when (message) {
            is AssistantMessage -> {
                message.content.filterIsInstance<ToolCall>().forEach { call ->
                    usedNames += normalizeName(call.name)
                }
            }

            is ToolResultMessage -> {
                message.addedToolNames.orEmpty().forEach { name ->
                    val normalizedName = normalizeName(name)
                    if (normalizedName !in usedNames) deferredNames += normalizedName
                }
            }

            is UserMessage -> {}
        }
    }

    val immediate = mutableListOf<Tool>()
    val deferred = linkedMapOf<String, Tool>()
    uniqueTools.forEach { (name, tool) ->
        if (name in deferredNames) deferred[name] = tool else immediate += tool
    }
    return DeferredToolPlacement(immediate, deferred)
}

/** Pi's deterministic short hash used for client-generated Responses item ids. */
internal fun deferredToolShortHash(value: String): String {
    var h1 = 0xDEADBEEF.toInt()
    var h2 = 0x41C6CE57.toInt()
    value.forEach { character ->
        val code = character.code
        h1 = (h1 xor code) * 2654435761.toInt()
        h2 = (h2 xor code) * 1597334677.toInt()
    }
    h1 = ((h1 xor (h1 ushr 16)) * 2246822507.toInt()) xor
        ((h2 xor (h2 ushr 13)) * 3266489909.toInt())
    h2 = ((h2 xor (h2 ushr 16)) * 2246822507.toInt()) xor
        ((h1 xor (h1 ushr 13)) * 3266489909.toInt())
    return h2.toUInt().toString(36) + h1.toUInt().toString(36)
}
