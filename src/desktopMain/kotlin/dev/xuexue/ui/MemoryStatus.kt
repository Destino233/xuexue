@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.xuexue.platform.JvmMemorySnapshot
import dev.xuexue.platform.currentJvmMemorySnapshot
import dev.xuexue.platform.formatMemoryMiB
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun MemoryStatusIndicator(modifier: Modifier = Modifier) {
    val snapshot = rememberJvmMemorySnapshot()
    val processBytes = snapshot.processMemory.residentBytes
    val processMiB = snapshot.processMemory.residentMiB
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    listOf(
                        processBytes?.let {
                            "主进程 RSS：${formatMemoryMiB(it)} " +
                                "（${snapshot.processMemory.source.description}）"
                        } ?: "主进程 RSS：当前平台暂未接入（${snapshot.processMemory.source.description}）",
                        snapshot.processMemory.pssBytes?.let {
                            "主进程 PSS：${formatMemoryMiB(it)}"
                        },
                        "Java 堆：已用 ${formatMemoryMiB(snapshot.usedBytes)} / " +
                            "上限 ${formatMemoryMiB(snapshot.maxBytes)}，已提交 ${formatMemoryMiB(snapshot.committedBytes)}",
                    ).filterNotNull().joinToString("\n"),
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        Text(
            processMiB?.let { "进程 $it MiB" } ?: "堆 ${snapshot.usedMiB}/${snapshot.maxMiB} MiB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = modifier.width(108.dp),
        )
    }
}

@Composable
internal fun rememberJvmMemorySnapshot(): JvmMemorySnapshot {
    val snapshot by
        produceState(initialValue = currentJvmMemorySnapshot()) {
            while (isActive) {
                delay(1_000L)
                value = currentJvmMemorySnapshot()
            }
        }
    return snapshot
}
