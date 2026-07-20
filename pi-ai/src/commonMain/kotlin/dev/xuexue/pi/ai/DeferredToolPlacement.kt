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
internal fun deferredToolShortHash(value: String): String = shortHash(value)
