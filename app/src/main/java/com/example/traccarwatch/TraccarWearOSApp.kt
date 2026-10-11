package com.example.traccarwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class TraccarWearOSApp : Application() {

    lateinit var tracker: Tracker
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

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
                if (enabled) showOngoing() else hideOngoing()
            }
        }
    }

    // Ongoing Activity: Wear OS shows a small icon at the bottom of the watch face that
    // opens the app while tracking is running.
    private fun showOngoing() {
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
        val builder = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("Traccar")
            .setContentText("Tracking")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(touchIntent)
        OngoingActivity.Builder(this, ONGOING_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_tracking)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate("Tracking").build())
            .build()
            .apply(this)
        manager.notify(ONGOING_NOTIFICATION_ID, builder.build())
    }

    private fun randomDeviceId() = (100000..999999).random().toString()

    private fun hideOngoing() {
        getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
    }
}
