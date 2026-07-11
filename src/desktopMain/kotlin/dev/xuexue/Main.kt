package dev.xuexue

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.xuexue.ui.WorkbenchApp
import java.awt.Dimension

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { WorkbenchState(scope) }
    var closeRequested by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            if (state.documents.any { it.dirty }) closeRequested = true else exitApplication()
        },
        title = state.workspaceRoot?.fileName?.let { "$it - XueXue" } ?: "XueXue",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        window.minimumSize = Dimension(840, 600)
        LaunchedEffect(Unit) { state.restoreLastWorkspace() }
        WorkbenchApp(
            state = state,
            closeRequested = closeRequested,
            onCancelClose = { closeRequested = false },
            onConfirmClose = {
                state.documents.filter { it.dirty }.forEach { state.selectTab(it.relativePath); state.saveActive() }
                exitApplication()
            },
        )
    }
}

