package dev.xuexue.platform

import kotlinx.serialization.Serializable

@Serializable
data class DocumentAnalysisSettings(
    val globalEngineId: String = DEFAULT_ANALYSIS_ENGINE_ID,
    val perDocumentEngineIds: Map<String, String> = emptyMap(),
    val allowLowConfidenceVlFallback: Boolean = false,
    val dpi: Int = DEFAULT_ANALYSIS_DPI,
    val language: String = DEFAULT_ANALYSIS_LANGUAGE,
) {
    init {
        require(globalEngineId.isNotBlank()) { "A default document analysis engine is required." }
        require(dpi in 72..600) { "Document analysis DPI must be between 72 and 600." }
        require(language.isNotBlank()) { "Document analysis language must not be blank." }
    }

    fun engineIdFor(relativePath: String): String = perDocumentEngineIds[relativePath] ?: globalEngineId

    fun withDocumentEngine(
        relativePath: String,
        engineId: String?,
    ): DocumentAnalysisSettings =
        copy(
            perDocumentEngineIds =
                perDocumentEngineIds.toMutableMap().apply {
                    if (engineId == null) remove(relativePath) else put(relativePath, engineId)
                },
        )
}

const val DEFAULT_ANALYSIS_ENGINE_ID = "pp-structure-v3"
const val DEFAULT_ANALYSIS_DPI = 300
const val DEFAULT_ANALYSIS_LANGUAGE = "chi_sim,en"
