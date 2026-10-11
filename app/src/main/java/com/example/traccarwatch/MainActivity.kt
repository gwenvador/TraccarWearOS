package com.example.traccarwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import org.traccar.client.Config
import org.traccar.client.LocationConfig
import org.traccar.client.Tracker
import org.traccar.client.startTracking

class MainActivity : ComponentActivity() {

    var resumeTick by mutableIntStateOf(0)
        private set

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val foregroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionCallback?.invoke(foregroundGranted()) }

    private val backgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionCallback?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tracker = (application as TraccarWearOSApp).tracker
        setContent {
            MaterialTheme {
                AppScaffold { TrackerScreen(tracker, this@MainActivity) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // Same set the SDK insists on; if any is missing its own prompt would hang on Wear OS.
    private fun foregroundPermissions() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )

    fun foregroundGranted() = foregroundPermissions().all(::granted)

    fun backgroundGranted() = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun allGranted() = foregroundGranted() && backgroundGranted()

    fun requestForeground(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        foregroundLauncher.launch(foregroundPermissions().toTypedArray())
    }

    fun requestBackground(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}

@Composable
private fun TrackerScreen(initialTracker: Tracker, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    var tracker by remember { mutableStateOf(initialTracker) }
    var serverUrl by remember { mutableStateOf(initialTracker.config.serverUrl) }
    var deviceId by remember { mutableStateOf(initialTracker.config.deviceId) }
    var byDistance by remember { mutableStateOf(initialTracker.config.location.byDistance()) }
    var amount by remember { mutableStateOf(initialTracker.config.location.amount().toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    val state by tracker.state.collectAsState()
    val config = tracker.config
    // An empty or zero value is ignored until it becomes valid.
    val newLocation = amount.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
        if (byDistance) config.location.copy(distanceMeters = n, intervalSeconds = 0)
        else config.location.copy(distanceMeters = 0, intervalSeconds = n)
    } ?: config.location
    val dirty = serverUrl != config.serverUrl || deviceId != config.deviceId ||
        newLocation != config.location

    var settingsHint by remember { mutableStateOf(false) }
    // Re-evaluated when the user comes back from the system settings.
    val needSettings = activity.resumeTick >= 0 && settingsHint && !activity.allGranted()

    fun begin() {
        scope.launch {
            try {
                tracker.startTracking(activity)
                // Tracking runs in the SDK's foreground service; leaving the
                // activity lets the watch turn its screen off.
                activity.finish()
            } catch (_: IllegalStateException) {
                error = "Location permission denied"
                settingsHint = true
            }
        }
    }

    fun attemptStart() {
        when {
            !activity.foregroundGranted() -> activity.requestForeground { ok ->
                if (ok) attemptStart() else settingsHint = true
            }
            !activity.backgroundGranted() -> activity.requestBackground { ok ->
                if (ok) begin() else settingsHint = true
            }
            else -> begin()
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    when {
                        !state.enabled -> "Stopped"
                        state.paused -> "Tracking (still)"
                        else -> "Tracking"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            state.lastAcceptedLocation?.let { p ->
                item {
                    Text(
                        "%.5f, %.5f".format(p.latitude, p.longitude),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        error = null
                        if (state.enabled) {
                            scope.launch { tracker.stop() }
                        } else {
                            attemptStart()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.enabled) "Stop" else "Start") }
            }
            if (needSettings) {
                item {
                    Text(
                        "Location must be \"Allow all the time\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Button(
                        onClick = { activity.openAppSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open settings") }
                }
            }
            error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            item {
                Button(
                    onClick = {
                        byDistance = !byDistance
                        amount = if (byDistance) "5" else "10"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (byDistance) "Mode: distance" else "Mode: time") }
            }
            item {
                Field(
                    if (byDistance) "Every (meters)" else "Every (seconds)",
                    amount,
                    KeyboardType.Number,
                ) { amount = it.filter(Char::isDigit) }
            }
            item { Field("Device ID", deviceId, KeyboardType.Ascii) { deviceId = it } }
            item { Field("Server URL", serverUrl, KeyboardType.Uri) { serverUrl = it } }
            if (dirty) {
                item {
                    Button(
                        onClick = {
                            scope.launch {
                                val wasEnabled = state.enabled
                                tracker = tracker.updateConfig(
                                    config.copy(serverUrl = serverUrl.trim(), deviceId = deviceId.trim(), location = newLocation),
                                )
                                (activity.application as TraccarWearOSApp).observe(tracker)
                                if (wasEnabled) tracker.start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


private fun LocationConfig.byDistance() = distanceMeters > 0

private fun LocationConfig.amount() = if (byDistance()) distanceMeters else intervalSeconds
