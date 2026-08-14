@file:Suppress("FunctionNaming")

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Compose Smoke") {
        // Auto-exit so the tracing agent can flush configs in CI-like runs.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(6_000)
            exitApplication()
        }
        SmokeScreen()
    }
}

@Composable
private fun SmokeScreen() {
    var count by remember { mutableIntStateOf(0) }
    val alpha by animateFloatAsState(if (count % 2 == 0) 1f else 0.4f)
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF1B1D1C)).clickable { count++ }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("compose smoke: $count", color = Color.White.copy(alpha = alpha))
            Button(onClick = { count++ }, modifier = Modifier.padding(top = 16.dp)) { Text("click") }
        }
    }
}
