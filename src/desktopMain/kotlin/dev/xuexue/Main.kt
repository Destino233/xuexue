package dev.xuexue

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.xuexue.ui.WindowChrome
import dev.xuexue.ui.WorkbenchApp
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() =
    application {
        val scope = rememberCoroutineScope()
        val state = remember { WorkbenchState(scope) }
        val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
        var closeRequested by remember { mutableStateOf(false) }
        val requestClose = {
            if (state.documents.any { it.dirty }) {
                closeRequested = true
            } else {
                state.persistLayout()
                state.shutdown()
                exitApplication()
            }
        }

        Window(
            onCloseRequest = requestClose,
            title = state.workspaceRoot?.fileName?.let { "$it - XueXue" } ?: "XueXue",
            state = windowState,
            undecorated = true,
        ) {
            window.minimumSize = Dimension(720, 560)
            LaunchedEffect(Unit) { state.restoreLastWorkspace() }
            WorkbenchApp(
                state = state,
                closeRequested = closeRequested,
                onCancelClose = { closeRequested = false },
                onConfirmClose = {
                    scope.launch {
                        if (state.saveAllDirty()) {
                            state.persistLayout()
                            state.shutdown()
                            exitApplication()
                        } else {
                            closeRequested = false
                        }
                    }
                },
                windowChrome =
                    WindowChrome(
                        titleBar = { modifier, content ->
                            WindowDraggableArea(modifier) { content() }
                        },
                        maximized = windowState.placement == WindowPlacement.Maximized,
                        onMinimize = { windowState.isMinimized = true },
                        onToggleMaximize = {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                        },
                        onClose = requestClose,
                    ),
            )
        }
    }
