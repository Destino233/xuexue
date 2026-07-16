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

            service.saveAppPreferences(AppPreferences(documentWheelZoomMultiplier = 1.75f))

            assertEquals(1.75f, service.loadAppPreferences().documentWheelZoomMultiplier)
            assertEquals(2f, normalizedDocumentWheelZoomMultiplier(20f))
            assertEquals(1f, normalizedDocumentWheelZoomMultiplier(Float.NaN))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
