package dev.xuexue.platform

import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPreferencesTest {
    @Test
    fun documentWheelZoomMultiplierPersistsAndNormalizes() {
        val root = Files.createTempDirectory("xuexue-app-preferences")
        try {
            val service = WorkspaceService(root)

            service.saveAppPreferences(
                AppPreferences(
                    documentWheelZoomMultiplier = 1.75f,
                    documentAnalysisSettings =
                        DocumentAnalysisSettings(
                            globalEngineId = NativePdfLayoutEngine().descriptor.engineId,
                            perDocumentEngineIds = mapOf("scan.pdf" to PpStructureV3Engine.ENGINE_ID),
                            allowLowConfidenceVlFallback = true,
                        ),
                ),
            )

            assertEquals(1.75f, service.loadAppPreferences().documentWheelZoomMultiplier)
            val restored = service.loadAppPreferences()
            assertEquals(NativePdfLayoutEngine().descriptor.engineId, restored.documentAnalysisSettings.globalEngineId)
            assertEquals(
                PpStructureV3Engine.ENGINE_ID,
                restored.documentAnalysisSettings.perDocumentEngineIds["scan.pdf"],
            )
            assertEquals(true, service.loadAppPreferences().documentAnalysisSettings.allowLowConfidenceVlFallback)
            assertEquals(2f, normalizedDocumentWheelZoomMultiplier(20f))
            assertEquals(1f, normalizedDocumentWheelZoomMultiplier(Float.NaN))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
