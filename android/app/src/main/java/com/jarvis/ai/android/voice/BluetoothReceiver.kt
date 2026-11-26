package com.jarvis.ai.android.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.android.screen.ScreenHub
import com.jarvis.ai.ui.MainActivity
import com.jarvis.ai.voice.BluetoothRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs when a Bluetooth device connects (Android delivers this only with the Nearby devices permission).
 *
 * It waits a few seconds for Android to route sound to the new device, and does nothing if it was not an audio device. Then, as the person
 * chose: says how many notifications are waiting (a count, never their words), and, if hands-free listening was on, shows a one-tap
 * "Start listening" notification.
 *
 * Why a notification and not simply starting to listen: since Android 14 the microphone cannot be started by a background broadcast.
 * The person taps the notification, which opens the app, and the app then starts the listener while it is on screen. Nothing here starts
 * the microphone by itself. (Not tried on a phone yet.)
 */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val container = (context.applicationContext as JarvisApplication).container
        val ui = container.settings.ui.value
        val offerListening = container.settings.handsFreeOn && !HandsFreeService.isRunning
        if (!ui.announceNotifications && !offerListening) return

        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TOTAL_WAIT_MS) {
                    val device = waitForAudioDevice(context) ?: return@withTimeoutOrNull
                    val name = BluetoothAudio.name(device) ?: nameFrom(intent)
                    if (!BluetoothRules.matches(name, container.settings.bluetoothDevices)) return@withTimeoutOrNull

                    if (ui.announceNotifications) {
                        val waiting = ScreenHub.notifications?.showing()?.size
                        container.voice.speakPrivately(BluetoothRules.connectedLine(name, waiting))
                    }
                    if (offerListening) offerToListen(context, name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing here is worth crashing the app for (a Bluetooth, speech or notification service that misbehaves): skip the announcement.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun waitForAudioDevice(context: Context): android.media.AudioDeviceInfo? {
        var waited = 0L
        while (waited <= AUDIO_WAIT_MS) {
            BluetoothAudio.connected(context)?.let { return it }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return null
    }

    private fun nameFrom(intent: Intent): String? = try {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.name?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: SecurityException) {
        null
    }

    private fun offerToListen(context: Context, deviceName: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bluetooth connected", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Offers to start listening for \"Jarvis\" when a Bluetooth headset or speaker connects"
            },
        )
        // A tap opens the app (so it is on screen, which Android requires) and the app then starts the listener. Starting the microphone
        // service straight from the notification would depend on Android treating the tap as an exemption, which varies by phone.
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_HANDS_FREE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val start = PendingIntent.getActivity(
            context,
            REQUEST_START,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (deviceName != null) "Bluetooth connected: $deviceName" else "Bluetooth connected")
            .setContentText("Tap to open Jarvis and start listening for \"Jarvis\".")
            .setContentIntent(start)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        const val CHANNEL_ID = "bluetooth_connected"
        const val NOTIFICATION_ID = 4103
        const val REQUEST_START = 41
        const val POLL_MS = 500L
        const val AUDIO_WAIT_MS = 7_000L
        const val TOTAL_WAIT_MS = 9_000L
        const val NOTIFICATION_TIMEOUT_MS = 10 * 60_000L
    }
}
