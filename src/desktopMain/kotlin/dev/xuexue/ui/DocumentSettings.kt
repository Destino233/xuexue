@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.xuexue.WorkbenchState
import dev.xuexue.platform.DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER
import dev.xuexue.platform.MAX_DOCUMENT_WHEEL_ZOOM_MULTIPLIER
import dev.xuexue.platform.MIN_DOCUMENT_WHEEL_ZOOM_MULTIPLIER
import java.util.Locale

@Composable
internal fun DocumentSettingsPane(state: WorkbenchState) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "文档滚轮放大倍率",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                documentWheelZoomMultiplierLabel(state.documentWheelZoomMultiplier),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            EditorTool(Icons.Default.Restore, "恢复默认倍率") {
                state.setDocumentWheelZoomMultiplier(DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER)
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = state.documentWheelZoomMultiplier,
            onValueChange = { value -> state.setDocumentWheelZoomMultiplier(value, persist = false) },
            onValueChangeFinished = state::persistDocumentPreferences,
            valueRange = MIN_DOCUMENT_WHEEL_ZOOM_MULTIPLIER..MAX_DOCUMENT_WHEEL_ZOOM_MULTIPLIER,
            steps = DOCUMENT_WHEEL_ZOOM_SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                documentWheelZoomMultiplierLabel(MIN_DOCUMENT_WHEEL_ZOOM_MULTIPLIER),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                documentWheelZoomMultiplierLabel(MAX_DOCUMENT_WHEEL_ZOOM_MULTIPLIER),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

internal fun documentWheelZoomMultiplierLabel(value: Float): String = String.format(Locale.ROOT, "%.2f×", value)

private const val DOCUMENT_WHEEL_ZOOM_SLIDER_STEPS = 5
