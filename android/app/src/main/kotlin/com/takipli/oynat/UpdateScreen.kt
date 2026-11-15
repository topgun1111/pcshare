package com.takipli.oynat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Home -> Updates: check GitHub Releases, download the newer signed APK of this flavor, hand it to the system installer. */
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf(Updater.repo(ctx)) }
    var token by remember { mutableStateOf(Updater.token(ctx)) }
    var found by remember { mutableStateOf<Updater.Found?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        ErrorLog.record(ctx, "update", e)
        status = ctx.getString(R.string.update_error, e.message ?: e.javaClass.simpleName)
    }

    fun installNow(file: File) {
        if (!Updater.canInstall(ctx)) { status = ctx.getString(R.string.update_allow); Updater.openInstallSettings(ctx) }
        else Updater.install(ctx, file)
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER))
        Text(stringResource(R.string.update_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.update_repo)) }, singleLine = true)
        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.update_token)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            enabled = !busy,
            onClick = {
                if (!Updater.repoValid(repo)) { status = ctx.getString(R.string.update_no_repo); return@Button }
                Updater.save(ctx, repo, token)
                busy = true; found = null; apk = null; status = ctx.getString(R.string.update_checking)
                scope.launch {
                    try {
                        val f = Updater.check(ctx)
                        found = f
                        status = if (f == null) ctx.getString(R.string.update_uptodate) else ctx.getString(R.string.update_found, f.label)
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) { fail(e) } finally { busy = false }
                }
            },
        ) { Text(stringResource(R.string.update_check)) }
        found?.let { f ->
            Button(
                enabled = !busy,
                onClick = {
                    busy = true; progress = 0f
                    scope.launch {
                        try {
                            val file = Updater.download(ctx, f) { done, total ->
                                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                                status = ctx.getString(R.string.update_downloading, if (total > 0) (done * 100 / total).toInt() else 0)
                            }
                            apk = file; progress = null
                            installNow(file)
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) { progress = null; fail(e) } finally { busy = false }
                    }
                },
            ) { Text(stringResource(R.string.update_download)) }
        }
        apk?.let { file -> Button(enabled = !busy, onClick = { installNow(file) }) { Text(stringResource(R.string.update_install)) } }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        if (status.isNotEmpty()) Text(status)
    }
}
