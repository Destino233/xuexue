@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    LaunchedEffect(Unit) { state.refreshDocumentAnalysisHardware() }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
        Spacer(Modifier.height(24.dp))
        DocumentAnalysisSettingsSection(state)
    }
}

@Composable
private fun DocumentAnalysisSettingsSection(state: WorkbenchState) {
    var menuOpen by remember { mutableStateOf(false) }
    val descriptors = state.documentAnalysisEngineDescriptors.associateBy { descriptor -> descriptor.engineId }
    val selectedDescriptor = descriptors[state.documentAnalysisSettings.globalEngineId]
    Text("文档识别", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "按页优先使用 PDF 原生文字层，扫描页使用所选版面识别方案。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("默认方案", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(selectedDescriptor?.displayName ?: state.documentAnalysisSettings.globalEngineId)
            }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                state.documentAnalysisEngineDescriptors.forEach { descriptor ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(descriptor.displayName) },
                        onClick = {
                            state.setDocumentAnalysisEngine(descriptor.engineId)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("低置信度启用 VL 回退", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = state.documentAnalysisSettings.allowLowConfidenceVlFallback,
            onCheckedChange = state::setDocumentAnalysisVlFallback,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("本机配置", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val profile = state.documentAnalysisHostComputeProfile
        Text(
            profile?.let { host -> "${host.cpuThreads} 核 · ${host.os} · ${host.architecture}" } ?: "未检测",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EditorTool(Icons.Default.Refresh, "重新检测并启动识别运行时", onClick = {
            state.refreshDocumentAnalysisHardware(startRuntime = true)
        })
    }
    Spacer(Modifier.height(8.dp))
    state.documentAnalysisAvailabilities.forEach { availability ->
        val descriptor = descriptors[availability.engineId]
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    descriptor?.displayName ?: availability.engineId,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (availability.recommended) {
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                availabilityDetail(availability, descriptor),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun availabilityDetail(
    availability: dev.xuexue.platform.EngineAvailability,
    descriptor: dev.xuexue.platform.DocumentAnalysisEngineDescriptor?,
): String {
    val capability =
        descriptor
            ?.let { item ->
                listOfNotNull(
                    item.languages.takeIf(Set<String>::isNotEmpty)?.joinToString("/"),
                    if (item.supportsTables) "表格" else null,
                    if (item.supportsVerticalText) "竖排" else null,
                    item.privacy.name,
                ).joinToString(" · ")
            }.orEmpty()
    val runtime = availability.runtimeVersion?.let { version -> "sidecar $version" }
    return listOfNotNull(availabilityLabel(availability.state), availability.estimatedSpeed, runtime, capability)
        .joinToString(" · ")
}

private fun availabilityLabel(state: dev.xuexue.platform.AvailabilityState): String =
    when (state) {
        dev.xuexue.platform.AvailabilityState.AVAILABLE -> "可运行"
        dev.xuexue.platform.AvailabilityState.AVAILABLE_WITH_WARNING -> "可运行 · 有警告"
        dev.xuexue.platform.AvailabilityState.DOWNLOAD_REQUIRED -> "需要下载"
        dev.xuexue.platform.AvailabilityState.UNSUPPORTED -> "不支持"
        dev.xuexue.platform.AvailabilityState.INSUFFICIENT_MEMORY -> "内存不足"
        dev.xuexue.platform.AvailabilityState.BROKEN_INSTALLATION -> "安装异常"
    }

internal fun documentWheelZoomMultiplierLabel(value: Float): String = String.format(Locale.ROOT, "%.2f×", value)

private const val DOCUMENT_WHEEL_ZOOM_SLIDER_STEPS = 5
