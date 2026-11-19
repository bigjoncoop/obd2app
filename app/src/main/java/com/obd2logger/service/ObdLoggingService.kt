package com.obd2logger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.obd2logger.MainActivity
import com.obd2logger.R
import com.obd2logger.data.logging.LoggingSessionCoordinator
import com.obd2logger.domain.model.LoggingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps OBD2 polling and 1Hz CSV logging alive when the screen is off
 * or the user switches apps. Shows a persistent low-priority notification with elapsed time,
 * row count, and a quick Stop action.
 */
@AndroidEntryPoint
class ObdLoggingService : Service() {

    @Inject
    lateinit var loggingCoordinator: LoggingSessionCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LOGGING) {
            loggingCoordinator.stopLogging()
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(
            title = "OBD2 Logger Active",
            text = "Preparing diagnostic session..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeLoggingState()
        return START_STICKY
    }

    private fun observeLoggingState() {
        if (stateObserverJob?.isActive == true) return
        val manager = getSystemService(NotificationManager::class.java)
        stateObserverJob = serviceScope.launch {
            loggingCoordinator.loggingState.collect { state ->
                when (state) {
                    is LoggingState.Preparing -> {
                        manager?.notify(
                            NOTIFICATION_ID,
                            buildNotification("OBD2 Logger • Preparing", state.stepMessage)
                        )
                    }
                    is LoggingState.Logging -> {
                        val timer = formatElapsed(state.elapsedMs)
                        val status = if (state.isAdapterConnected) {
                            "Logging • $timer • ${state.rowCount} rows"
                        } else {
                            "Reconnecting (writing '-') • $timer • ${state.rowCount} rows"
                        }
                        manager?.notify(
                            NOTIFICATION_ID,
                            buildNotification(state.filename, status)
                        )
                    }
                    LoggingState.Idle,
                    is LoggingState.Stopped,
                    is LoggingState.Error -> {
                        // Service stop is triggered by coordinator; guard here as fallback
                    }
                }
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ObdLoggingService::class.java).apply {
            action = ACTION_STOP_LOGGING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop Logging", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBD2 Logging Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OBD2 CSV data logging active with screen off"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBD2Logger:LoggingWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12h safety timeout
        }
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        serviceScope.cancel()
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "obd2_logging_channel"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_STOP_LOGGING = "com.obd2logger.action.STOP_LOGGING"

        fun start(context: Context) {
            val intent = Intent(context, ObdLoggingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ObdLoggingService::class.java)
            context.stopService(intent)
        }

        fun formatElapsed(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSec / 3600L
            val mins = (totalSec % 3600L) / 60L
            val secs = totalSec % 60L
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, mins, secs)
            } else {
                "%02d:%02d".format(mins, secs)
            }
        }
    }
}
