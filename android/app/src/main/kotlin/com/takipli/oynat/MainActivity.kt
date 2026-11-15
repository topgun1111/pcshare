package com.takipli.oynat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takipli.oynat.core.CoreInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf("home") }
                    var resultFile by remember { mutableStateOf("") }
                    when (screen) {
                        "bench" -> BenchScreen(onBack = { screen = "home" })
                        "decode" -> DecodeScreen(onBack = { screen = "home" })
                        "scan" -> ScanScreen(onBack = { screen = "home" })
                        "pick" -> PickScreen(onBack = { screen = "home" }, onResult = { resultFile = it; screen = "result" })
                        "result" -> ResultScreen(resultFile, onBack = { screen = "pick" })
                        "update" -> UpdateScreen(onBack = { screen = "home" })
                        else -> HomeScreen(onBench = { screen = "bench" }, onDecode = { screen = "decode" }, onScan = { screen = "scan" }, onPick = { screen = "pick" }, onUpdate = { screen = "update" })
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(onBench: () -> Unit, onDecode: () -> Unit, onScan: () -> Unit, onPick: () -> Unit, onUpdate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, alignment = androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.core_check, CoreInfo.label()))
        Button(onClick = onPick) { Text(stringResource(R.string.open_pick)) }
        Button(onClick = onBench) { Text(stringResource(R.string.open_bench)) }
        Button(onClick = onDecode) { Text(stringResource(R.string.open_decode)) }
        Button(onClick = onScan) { Text(stringResource(R.string.open_scan)) }
        Button(onClick = onUpdate) { Text(stringResource(R.string.open_update)) }
        AboutSection()
    }
}
