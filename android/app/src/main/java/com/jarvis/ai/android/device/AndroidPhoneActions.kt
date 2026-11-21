package com.jarvis.ai.android.device

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.jarvis.ai.ai.tools.builtin.CameraLauncher
import com.jarvis.ai.ai.tools.builtin.EmailPort
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.MediaAction
import com.jarvis.ai.ai.tools.builtin.MediaKeys
import com.jarvis.ai.ai.tools.builtin.MediaResult
import com.jarvis.ai.ai.tools.builtin.MessageHandoff
import com.jarvis.ai.ai.tools.builtin.MessagingPort
import com.jarvis.ai.ai.tools.builtin.TimerLauncher

private fun LaunchResult.toHandoff(): MessageHandoff = when (this) {
    LaunchResult.Launched -> MessageHandoff.Opened
    is LaunchResult.NoHandler -> MessageHandoff.NoApp(what)
    is LaunchResult.Failed -> MessageHandoff.Failed(reason)
}

/**
 * Opens the messages app or WhatsApp with the text already typed in. It never presses Send and never taps or types inside
 * another app: the person reads the message there and sends it themselves. WhatsApp is addressed by package so a missing
 * WhatsApp is reported as that instead of the link opening in a browser.
 */
class AndroidMessagingPort(
    context: Context,
    private val foreground: ForegroundTracker,
) : MessagingPort {

    private val context = context.applicationContext

    override fun composeSms(number: String, body: String): MessageHandoff {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body)
        return startActivitySafely(context, foreground, intent, "text messages").toHandoff()
    }

    override fun openWhatsAppChat(internationalNumber: String, body: String): MessageHandoff {
        val digits = internationalNumber.filter { it.isDigit() }
        val link = Uri.Builder().scheme("https").authority("wa.me").appendPath(digits).appendQueryParameter("text", body).build()
        return startWhatsApp { pkg -> Intent(Intent.ACTION_VIEW, link).setPackage(pkg) }
    }

    override fun shareToWhatsApp(body: String): MessageHandoff =
        startWhatsApp { pkg -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).setPackage(pkg) }

    private fun startWhatsApp(build: (String) -> Intent): MessageHandoff {
        for (pkg in WHATSAPP_PACKAGES) {
            when (val result = startActivitySafely(context, foreground, build(pkg), "WhatsApp")) {
                LaunchResult.Launched -> return MessageHandoff.Opened
                is LaunchResult.Failed -> return MessageHandoff.Failed(result.reason)
                is LaunchResult.NoHandler -> Unit // not this one; try WhatsApp Business
            }
        }
        return MessageHandoff.NoApp("WhatsApp")
    }

    private companion object {
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

/**
 * Opens the person's email app with a new draft (address, subject and text already typed in). Only email apps answer a
 * `mailto:` SENDTO request, so a text-sharing app never opens by mistake. It never presses Send.
 */
class AndroidEmailPort(
    context: Context,
    private val foreground: ForegroundTracker,
) : EmailPort {

    private val context = context.applicationContext

    override fun compose(to: String?, subject: String, body: String): MessageHandoff {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (to != null) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        if (subject.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, body)
        return startActivitySafely(context, foreground, intent, "email").toHandoff()
    }
}

/**
 * Opens the phone's own camera app in photo or video mode. These two actions only show the camera: they take nothing and need no
 * camera permission, and the person presses the shutter or record button.
 */
class AndroidCameraLauncher(
    context: Context,
    private val foreground: ForegroundTracker,
) : CameraLauncher {

    private val context = context.applicationContext

    override fun open(video: Boolean): LaunchResult {
        val action = if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return startActivitySafely(context, foreground, Intent(action), "the camera")
    }
}

/** Asks the system Clock app for a countdown. Android does not report whether it started, so the tool says "asked". */
class AndroidTimerLauncher(
    context: Context,
    private val foreground: ForegroundTracker,
) : TimerLauncher {

    private val context = context.applicationContext

    override fun start(seconds: Int, label: String?): LaunchResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startActivitySafely(context, foreground, intent, "timers")
    }
}

/**
 * Sends the same media buttons as a headset and changes the media volume. The buttons go to whichever app Android treats as the
 * active player; Android does not say whether one reacted, so the tool reports them as sent. Volume is read back from the
 * system before and after, so that result is confirmed.
 */
class AndroidMediaKeys(context: Context) : MediaKeys {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun perform(action: MediaAction, steps: Int): MediaResult {
        val manager = audio ?: return MediaResult.Failed("The audio service is not available on this phone.")
        return try {
            when (action) {
                MediaAction.VOLUME_UP -> changeVolume(manager, AudioManager.ADJUST_RAISE, steps)
                MediaAction.VOLUME_DOWN -> changeVolume(manager, AudioManager.ADJUST_LOWER, steps)
                else -> {
                    val code = when (action) {
                        MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                        MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                        MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> KeyEvent.KEYCODE_MEDIA_STOP
                    }
                    manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    MediaResult.Sent
                }
            }
        } catch (e: SecurityException) {
            MediaResult.Failed("Android blocked the request (${e.javaClass.simpleName}).")
        }
    }

    private fun changeVolume(manager: AudioManager, direction: Int, steps: Int): MediaResult {
        val before = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        repeat(steps) { manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, if (it == steps - 1) AudioManager.FLAG_SHOW_UI else 0) }
        return MediaResult.Volume(before, manager.getStreamVolume(AudioManager.STREAM_MUSIC), manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }
}
