@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.RemotePdfDownloadPhase
import dev.xuexue.RemotePdfDownloadState
import dev.xuexue.WorkbenchState

@Composable
internal fun PdfRemoteDownloadBar(
    state: WorkbenchState,
    download: RemotePdfDownloadState,
) {
    val failed = download.phase == RemotePdfDownloadPhase.FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(
                if (failed) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            ).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (download.phase) {
                RemotePdfDownloadPhase.DOWNLOADING -> Icons.Default.Download
                RemotePdfDownloadPhase.PAUSED -> Icons.Default.Pause
                RemotePdfDownloadPhase.FAILED -> Icons.Default.ErrorOutline
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            download.statusText(),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (download.phase == RemotePdfDownloadPhase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.width(104.dp).height(3.dp),
            )
        } else {
            EditorTool(Icons.Default.Refresh, "重试下载") {
                state.downloadRemotePdf(download.sourceUrl)
            }
        }
    }
}

private fun RemotePdfDownloadState.statusText(): String =
    when (phase) {
        RemotePdfDownloadPhase.DOWNLOADING -> "正在下载 · $byteProgress"
        RemotePdfDownloadPhase.PAUSED -> "下载已暂停 · $byteProgress"
        RemotePdfDownloadPhase.FAILED -> "下载中断 · $byteProgress"
    }
