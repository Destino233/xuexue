@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import dev.xuexue.platform.APPLICATION_MEMORY_STEP_MIB
import dev.xuexue.platform.MAX_APPLICATION_MEMORY_MIB
import dev.xuexue.platform.MIN_APPLICATION_MEMORY_MIB
import dev.xuexue.platform.defaultApplicationMemoryLimitMiB
import dev.xuexue.platform.formatMemoryMiB

@Composable
internal fun ApplicationSettingsPane(state: WorkbenchState) {
    val snapshot = rememberJvmMemorySnapshot()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("应用内存", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "设置 Java 堆的最大分配量。修改后重启应用自动生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分配内存大小", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${state.applicationMemoryLimitMiB} MiB",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            EditorTool(Icons.Default.Restore, "恢复当前 JVM 默认上限") {
                state.setApplicationMemoryLimitMiB(defaultApplicationMemoryLimitMiB())
            }
        }
        Slider(
            value = state.applicationMemoryLimitMiB.toFloat(),
            onValueChange = { value -> state.setApplicationMemoryLimitMiB(value.toInt(), persist = false) },
            onValueChangeFinished = state::persistDocumentPreferences,
            valueRange = MIN_APPLICATION_MEMORY_MIB.toFloat()..MAX_APPLICATION_MEMORY_MIB.toFloat(),
            steps = (MAX_APPLICATION_MEMORY_MIB - MIN_APPLICATION_MEMORY_MIB) / APPLICATION_MEMORY_STEP_MIB - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$MIN_APPLICATION_MEMORY_MIB MiB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "$MAX_APPLICATION_MEMORY_MIB MiB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            snapshot.processMemory.residentBytes?.let {
                buildString {
                    append("当前进程 RSS ${formatMemoryMiB(it)}")
                    snapshot.processMemory.pssBytes?.let { pss ->
                        append("；PSS ${formatMemoryMiB(pss)}")
                    }
                    append("；Java 堆已用 ")
                    append("${formatMemoryMiB(snapshot.usedBytes)} / 上限 ${formatMemoryMiB(snapshot.maxBytes)}")
                }
            } ?: "Java 堆已用 ${formatMemoryMiB(snapshot.usedBytes)} / 上限 ${formatMemoryMiB(snapshot.maxBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "建议不要超过本机物理内存，PDF 与识别运行时还会使用堆外内存。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
