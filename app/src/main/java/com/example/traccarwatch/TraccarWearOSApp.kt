package com.example.traccarwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.traccar.client.Accuracy
import org.traccar.client.Config
import org.traccar.client.LocationConfig
import org.traccar.client.Tracker
import org.traccar.client.sharedTracker

private const val DEFAULT_SERVER_URL = "https://demo.traccar.org/"

// Default preset for kitesurfing: a point every 5 m, GPS kept on (activity recognition may not
// flag a kite session as movement, which would pause tracking and lose the track).
private val LOCATION_CONFIG = LocationConfig(
    accuracy = Accuracy.HIGH,
    distanceMeters = 5,
    stopDetection = false,
)

private const val ONGOING_CHANNEL_ID = "ongoing"
private const val ONGOING_NOTIFICATION_ID = 4242

// How often the connection status is re-evaluated while tracking.
private const val STATUS_POLL_MS = 10_000L
// A queued position older than this means the server is not accepting uploads.
private const val STALE_QUEUE_MS = 60_000L

class TraccarWearOSApp : Application() {

    lateinit var tracker: Tracker
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var statusJob: Job? = null

    private val _connected = MutableStateFlow(false)
    /** True when the network is up and the SDK's upload queue is draining. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Wakes the status loop as soon as the network changes instead of waiting for the next poll.
    private val networkChanged = MutableStateFlow(0)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkChanged.value++ }
        override fun onLost(network: Network) { networkChanged.value++ }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { networkChanged.value++ }
    }
    private var networkCallbackRegistered = false

    override fun onCreate() {
        super.onCreate()
        // Restores the persisted config on a restart; falls back to defaults on first run.
        val initial = runBlocking {
            val loaded = sharedTracker()
                ?: sharedTracker(Config(DEFAULT_SERVER_URL, randomDeviceId(), LOCATION_CONFIG))
            if (loaded.config.location != LocationConfig()) {
                // The user has chosen their own tracking settings; keep them.
                loaded
            } else {
                // Still the untouched SDK default (75 m, stop detection): move to the kite preset.
                val wasEnabled = loaded.state.value.enabled
                loaded.updateConfig(loaded.config.copy(location = LOCATION_CONFIG))
                    .also { if (wasEnabled) it.start() }
            }
        }
        observe(initial)
    }

    /** Call whenever the tracker instance is replaced (e.g. after updateConfig). */
    fun observe(newTracker: Tracker) {
        tracker = newTracker
        observeJob?.cancel()
        observeJob = scope.launch {
            newTracker.state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) startStatusWatch() else stopStatusWatch()
            }
        }
    }

    private fun startStatusWatch() {
        if (statusJob?.isActive == true) return
        if (!networkCallbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            }
        }
        _connected.value = computeConnected()
        showOngoing(_connected.value)
        statusJob = scope.launch {
            // Re-check on every network change, and periodically to follow the upload queue.
            launch { networkChanged.collect { refreshStatus() } }
            while (isActive) {
                delay(STATUS_POLL_MS)
                refreshStatus()
            }
        }
    }

    private suspend fun refreshStatus() {
        val now = withContext(Dispatchers.IO) { computeConnected() }
        if (now != _connected.value) {
            _connected.value = now
            showOngoing(now)
        }
    }

    private fun stopStatusWatch() {
        statusJob?.cancel()
        statusJob = null
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        _connected.value = false
        hideOngoing()
    }

    private fun computeConnected(): Boolean = isOnline() && queueDraining()

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // Every position goes through the SDK's SQLite queue ("tracker.db", table Position) and is
    // removed once the server accepted it. An empty queue, or one whose oldest point is recent,
    // means uploads are getting through.
    private fun queueDraining(): Boolean {
        val file = getDatabasePath("tracker.db")
        if (!file.exists()) return true
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT MIN(time) FROM Position", null).use { c ->
                    if (!c.moveToFirst() || c.isNull(0)) return true
                    var oldest = c.getLong(0)
                    if (oldest < 100_000_000_000L) oldest *= 1000 // seconds -> ms
                    System.currentTimeMillis() - oldest < STALE_QUEUE_MS
                }
            }
        } catch (_: Exception) {
            true // can't tell: fall back to the network state alone
        }
    }

    // Ongoing Activity: Wear OS shows a small icon at the bottom of the watch face that
    // opens the app while tracking is running.
    private fun showOngoing(connected: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL_ID, "Tracking status", NotificationManager.IMPORTANCE_LOW),
        )
        val touchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @DrawableRes val icon = if (connected) R.drawable.ic_stat_tracking_ok else R.drawable.ic_stat_tracking_ko
        val label = if (connected) "Connected" else "Not connected"
        val builder = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(icon)
            .setColor(if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            .setContentTitle("Traccar")
            .setContentText("Tracking - $label")
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(touchIntent)
        OngoingActivity.Builder(this, ONGOING_NOTIFICATION_ID, builder)
            .setStaticIcon(icon)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate(label).build())
            .build()
            .apply(this)
        manager.notify(ONGOING_NOTIFICATION_ID, builder.build())
    }

    private fun randomDeviceId() = (100000..999999).random().toString()

    private fun hideOngoing() {
        getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
    }
}
