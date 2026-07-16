package dev.xuexue.pi.ai

private val EXTENDED_THINKING_LEVELS =
    listOf(
        ModelThinkingLevel.OFF,
        ModelThinkingLevel.MINIMAL,
        ModelThinkingLevel.LOW,
        ModelThinkingLevel.MEDIUM,
        ModelThinkingLevel.HIGH,
        ModelThinkingLevel.XHIGH,
        ModelThinkingLevel.MAX,
    )

fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel> {
    if (!model.reasoning) return listOf(ModelThinkingLevel.OFF)

    return EXTENDED_THINKING_LEVELS.filter { level ->
        val mapping = model.thinkingLevelMap
        val explicitlyMapped = mapping?.containsKey(level) == true
        val mapped = mapping?.get(level)
        when {
            explicitlyMapped && mapped == null -> false
            level == ModelThinkingLevel.XHIGH || level == ModelThinkingLevel.MAX -> explicitlyMapped
            else -> true
        }
    }
}

fun clampThinkingLevel(
    model: Model,
    level: ModelThinkingLevel,
): ModelThinkingLevel {
    val availableLevels = getSupportedThinkingLevels(model)
    val requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level)
    return when {
        level in availableLevels -> {
            level
        }

        requestedIndex == -1 -> {
            availableLevels.firstOrNull() ?: ModelThinkingLevel.OFF
        }

        else -> {
            EXTENDED_THINKING_LEVELS
                .drop(requestedIndex)
                .firstOrNull { it in availableLevels }
                ?: EXTENDED_THINKING_LEVELS
                    .take(requestedIndex)
                    .asReversed()
                    .firstOrNull { it in availableLevels }
                ?: availableLevels.firstOrNull()
                ?: ModelThinkingLevel.OFF
        }
    }
}

fun modelsAreEqual(
    first: Model?,
    second: Model?,
): Boolean =
    first != null &&
        second != null &&
        first.id == second.id &&
        first.provider == second.provider
