package com.jarvis.ai.android.device

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.jarvis.ai.ai.tools.builtin.AccessLine
import com.jarvis.ai.ai.tools.builtin.AccessReporter
import com.jarvis.ai.ai.tools.builtin.BrightnessResult
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.PaymentModeResult
import com.jarvis.ai.android.screen.ScreenHub
import com.jarvis.ai.android.voice.HandsFreeService
import com.jarvis.ai.ai.tools.builtin.PhoneSettingsPort
import com.jarvis.ai.ai.tools.builtin.SettingsPage
import com.jarvis.ai.ai.tools.builtin.SoundMode
import com.jarvis.ai.ai.tools.builtin.SoundResult
import kotlin.math.roundToInt

/**
 * Brightness, ringer mode, Settings pages, the clipboard and "dismiss the alarm".
 *
 * Every change is read back from Android before it is reported. A switch Android keeps for itself (Wi-Fi, Bluetooth, airplane
 * mode, mobile data) is never flipped here: its Settings page is opened and the tool says that is all that happened.
 */
class AndroidPhoneSettings(context: Context, private val foreground: ForegroundTracker) : PhoneSettingsPort {

    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    override fun brightnessPercent(): Int? = try {
        (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) * 100.0 / MAX_LEVEL).roundToInt().coerceIn(0, 100)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    override fun setBrightnessPercent(percent: Int): BrightnessResult {
        if (!Settings.System.canWrite(context)) return BrightnessResult.NeedsPermission
        return try {
            val before = brightnessPercent()
            val adaptive = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            // Automatic brightness would move the level again at once, so it is switched off for a level the person asked for.
            if (adaptive) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val level = (percent.coerceIn(1, 100) * MAX_LEVEL / 100.0).roundToInt().coerceIn(1, MAX_LEVEL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            val after = brightnessPercent() ?: return BrightnessResult.Failed("Android did not report the brightness after the change.")
            BrightnessResult.Changed(before, after, adaptive)
        } catch (e: SecurityException) {
            BrightnessResult.NeedsPermission
        } catch (e: RuntimeException) {
            BrightnessResult.Failed("Android refused the brightness change (${e.javaClass.simpleName}).")
        }
    }

    override fun setSoundMode(mode: SoundMode): SoundResult {
        val audio = context.getSystemService(AudioManager::class.java) ?: return SoundResult.Failed("The audio service is not available.")
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (mode == SoundMode.SILENT && notifications?.isNotificationPolicyAccessGranted != true) return SoundResult.NeedsPermission
        return try {
            audio.ringerMode = when (mode) {
                SoundMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                SoundMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                SoundMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            }
            SoundResult.Changed(
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> SoundMode.NORMAL
                    AudioManager.RINGER_MODE_VIBRATE -> SoundMode.VIBRATE
                    AudioManager.RINGER_MODE_SILENT -> SoundMode.SILENT
                    else -> null
                },
            )
        } catch (e: SecurityException) {
            SoundResult.NeedsPermission
        }
    }

    override fun open(page: SettingsPage): LaunchResult {
        val primary = intentFor(page)
        val first = startActivitySafely(context, foreground, primary, "the ${page.spoken} settings")
        if (first !is LaunchResult.NoHandler) return first
        // Some phones have no page of their own for this; the general Settings app is the honest fallback.
        val fallback = when (page) {
            SettingsPage.HOTSPOT, SettingsPage.MOBILE_NETWORK, SettingsPage.AIRPLANE_MODE, SettingsPage.INTERNET_PANEL, SettingsPage.VPN ->
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return startActivitySafely(context, foreground, fallback, "the settings")
    }

    private fun intentFor(page: SettingsPage): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (page) {
            SettingsPage.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsPage.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsPage.MOBILE_NETWORK -> Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            SettingsPage.AIRPLANE_MODE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            SettingsPage.HOTSPOT -> Intent("android.settings.TETHER_SETTINGS")
            SettingsPage.DATA_USAGE -> Intent("android.settings.DATA_USAGE_SETTINGS")
            SettingsPage.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            SettingsPage.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SettingsPage.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsPage.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsPage.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            SettingsPage.APPS -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            SettingsPage.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingsPage.NOTIFICATIONS -> Intent("android.settings.NOTIFICATION_SETTINGS")
            SettingsPage.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SettingsPage.DATE_TIME -> Intent(Settings.ACTION_DATE_SETTINGS)
            SettingsPage.LANGUAGE -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            SettingsPage.VPN -> Intent("android.net.vpn.SETTINGS")
            SettingsPage.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
            SettingsPage.PRIVACY -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            SettingsPage.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            SettingsPage.DEVELOPER -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            SettingsPage.DEFAULT_APPS -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            SettingsPage.INTERNET_PANEL -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            SettingsPage.VOLUME_PANEL -> Intent(Settings.Panel.ACTION_VOLUME)
            SettingsPage.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            SettingsPage.DISPLAY_OVER_APPS -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            SettingsPage.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)
            SettingsPage.DO_NOT_DISTURB_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            SettingsPage.THIS_APP -> AccessChecks.appSettingsIntent(context)
        }
    }

    override fun enterPaymentMode(): PaymentModeResult {
        val service = ScreenHub.accessibility
        var asked = false
        if (service != null) {
            try {
                service.switchOff()
                asked = true
            } catch (e: RuntimeException) {
                // Android refused; reported honestly below.
            }
        }
        val stopped = try {
            HandsFreeService.stop(context)
            true
        } catch (e: RuntimeException) {
            false
        }
        return PaymentModeResult(screenControlWasOn = service != null, screenControlAsked = asked, handsFreeStopped = stopped)
    }

    override fun dismissAlarm(): LaunchResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        return startActivitySafely(context, foreground, intent, "the alarm")
    }

    override fun copyToClipboard(text: String): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            false
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS 3.0", text))
            true
        }
    } catch (e: RuntimeException) {
        false
    }

    private companion object {
        const val MAX_LEVEL = 255
    }
}

/** Reads every access switch from Android now, for "which permissions are off?". Nothing is remembered between asks. */
class AndroidAccessReporter(context: Context) : AccessReporter {

    private val context = context.applicationContext

    override fun report(): List<AccessLine> = AccessKey.entries.filter { it.activeNow }.map { key ->
        AccessLine(key.title, AccessChecks.state(context, key) == AccessState.ALLOWED, UNLOCKS.getValue(key))
    }

    private companion object {
        /** One short line for what stays unavailable while the access is off. */
        val UNLOCKS = mapOf(
            AccessKey.NOTIFICATIONS to "I cannot show reminders or progress",
            AccessKey.BATTERY to "Android may stop me in the background",
            AccessKey.MICROPHONE to "I cannot hear you",
            AccessKey.CONTACTS to "I cannot look up a name to call or message",
            AccessKey.PHONE_CALLS to "I cannot place a call, only open the dialer",
            AccessKey.ANSWER_CALLS to "I cannot answer or hang up a call",
            AccessKey.EXACT_ALARMS to "reminders may arrive a few minutes late",
            AccessKey.OVERLAY to "I cannot open apps while JARVIS is not on screen",
            AccessKey.USAGE_ACCESS to "I cannot tell you how much mobile data was used",
            AccessKey.ACCESSIBILITY to "I cannot read the screen, scroll, press buttons or go back and home",
            AccessKey.BLUETOOTH to "I cannot notice a Bluetooth headset connecting",
            AccessKey.WRITE_SETTINGS to "I cannot change the brightness",
            AccessKey.DND_ACCESS to "I cannot put the phone on silent",
            AccessKey.NOTIFICATION_ACCESS to "I cannot read or clear your notifications",
        )
    }
}
