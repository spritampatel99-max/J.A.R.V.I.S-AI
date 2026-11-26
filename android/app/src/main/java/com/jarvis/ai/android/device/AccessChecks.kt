package com.jarvis.ai.android.device

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jarvis.ai.android.screen.JarvisAccessibilityService

enum class AccessKind {
    /** Android shows its own allow/deny dialog. */
    RUNTIME,

    /** A switch the user flips on a Settings page; the app can only open that page. */
    SPECIAL,

    /** Android has nothing to enable yet, because the feature that needs it is not built. */
    UNAVAILABLE,
}

enum class AccessState {
    ALLOWED,
    NOT_ALLOWED,

    /** This Android version grants it without asking. */
    NOT_NEEDED,
}

/**
 * Every kind of access the app can ask for. [activeNow] is the honest part: it says whether anything in the current app
 * actually uses the access today. Allowing an access that is not used yet is harmless, but the screen says so rather than
 * implying the feature exists.
 */
enum class AccessKey(
    val title: String,
    val kind: AccessKind,
    val manifestPermission: String?,
    val activeNow: Boolean,
    val purpose: String,
    val reach: String,
    val revoke: String,
) {
    NOTIFICATIONS(
        "Notifications", AccessKind.RUNTIME, Manifest.permission.POST_NOTIFICATIONS, true,
        "Shows reminders and automation notifications, and the progress of a model download.",
        "Nothing is read. The app can only show its own notifications.",
        "Settings > Apps > JARVIS 3.0 > Notifications.",
    ),
    BATTERY(
        "Run in the background", AccessKind.SPECIAL, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true,
        "Stops Android pausing a long model download when the screen turns off.",
        "Nothing is read. It only exempts the app from battery saving, which uses more battery while it works.",
        "Settings > Apps > Special access > Battery optimisation.",
    ),
    MICROPHONE(
        "Microphone", AccessKind.RUNTIME, Manifest.permission.RECORD_AUDIO, true,
        "For voice commands. It listens when you tap the mic button, and, only if you turn on Hands-free in Prefs, for the word \"Jarvis\".",
        "Sound is passed to Android's speech recogniser for each request. While Hands-free is on, a small on-device detector also hears the microphone, " +
            "but it only reports the word \"Jarvis\". The app does not record or store audio. " +
            "Depending on your phone, the recogniser may send the sound to its provider (usually Google) to turn it into text; " +
            "on Android 13 and later the app asks for the on-device recogniser when your phone has one.",
        "Settings > Apps > JARVIS 3.0 > Permissions > Microphone.",
    ),
    CONTACTS(
        "Contacts", AccessKind.RUNTIME, Manifest.permission.READ_CONTACTS, true,
        "To find a person by name when you ask to call them.",
        "Names and phone numbers matching the name you asked for, only when a command needs one. Contacts are never uploaded by the app, " +
            "and the confirmation shows the exact name and number before anything is dialled.",
        "Settings > Apps > JARVIS 3.0 > Permissions > Contacts.",
    ),
    PHONE_CALLS(
        "Phone calls", AccessKind.RUNTIME, Manifest.permission.CALL_PHONE, true,
        "To place a call you asked for, after you tap Confirm. Without it, the phone's dialer opens with the number filled in and you press call yourself.",
        "It lets the app start a call. It does not read your call history. Emergency numbers are never dialled by the app.",
        "Settings > Apps > JARVIS 3.0 > Permissions > Phone.",
    ),
    ANSWER_CALLS(
        "Answer and end calls", AccessKind.RUNTIME, Manifest.permission.ANSWER_PHONE_CALLS, true,
        "So you can say or type \"answer the call\" or \"hang up\". The app cannot see who is calling and does not watch for calls.",
        "It lets the app ask Android to pick up a ringing call or end the current one, only when you ask.",
        "Settings > Apps > JARVIS 3.0 > Permissions > Phone.",
    ),
    EXACT_ALARMS(
        "Exact alarms and reminders", AccessKind.SPECIAL, Manifest.permission.SCHEDULE_EXACT_ALARM, true,
        "Lets reminders and automations fire at the exact minute. Without it they still run, but Android may deliver them a few minutes late.",
        "Nothing is read. It only lets the app schedule precise timers.",
        "Settings > Apps > Special access > Alarms & reminders.",
    ),
    OVERLAY(
        "Display over other apps", AccessKind.SPECIAL, Manifest.permission.SYSTEM_ALERT_WINDOW, true,
        "Lets hands-free show a small card (\"Listening…\", the answer, the call countdown with Cancel) on top of other apps, and lets the app open " +
            "another app when you ask by voice while JARVIS 3.0 is not on screen. Without it, opening apps by voice works only while JARVIS 3.0 is open.",
        "It can draw over other apps. It cannot read what is on them.",
        "Settings > Apps > Special access > Display over other apps.",
    ),
    USAGE_ACCESS(
        "Usage access", AccessKind.SPECIAL, Manifest.permission.PACKAGE_USAGE_STATS, true,
        "For the mobile data monitor: \"how much data did I use today?\" and which apps used the most.",
        "Android gives one switch for data counts and screen time. Jarvis reads only the mobile data counts (how many bytes each app used). " +
            "It does not read what was sent, which sites, or how long any app was on screen.",
        "Settings > Apps > Special access > Usage access.",
    ),
    ACCESSIBILITY(
        "Phone control (Accessibility)", AccessKind.SPECIAL, null, true,
        "Lets you say \"read my screen\", \"scroll down\", \"go back\", \"go home\", \"lock the phone\" or \"take a screenshot\". " +
            "It reads the text of the window in front aloud and presses Android's own navigation buttons. With the extra switches in Prefs it can also press a labelled button, type, and press Send in WhatsApp for a message you confirmed.",
        "The text of the screen in front, only when you ask. It skips password fields, text boxes you type in, banking, payment and password apps, " +
            "and JARVIS 3.0 itself. Pressing buttons, typing and pressing Send in WhatsApp work only if you switch them on in Prefs > Phone control. What it reads is shown to you and spoken, not saved and not sent to an AI. " +
            "If Android greys the switch out (apps installed outside the Play Store), open Settings > Apps > JARVIS 3.0, tap the three dots and choose \"Allow restricted settings\", then try again.",
        "Settings > Accessibility > JARVIS 3.0 > switch off.",
    ),
    BLUETOOTH(
        "Nearby devices (Bluetooth)", AccessKind.RUNTIME, Manifest.permission.BLUETOOTH_CONNECT, true,
        "Lets JARVIS 3.0 notice when your Bluetooth headset or speaker connects, so it can say what is waiting, offer to start listening, and read new notifications aloud through it.",
        "The name of a Bluetooth device when it connects. It does not read what is sent over Bluetooth and does not scan for or pair devices.",
        "Settings > Apps > JARVIS 3.0 > Permissions > Nearby devices.",
    ),
    WRITE_SETTINGS(
        "Modify system settings", AccessKind.SPECIAL, Manifest.permission.WRITE_SETTINGS, true,
        "Lets you say \"set brightness to 60 percent\". Without it, Android does not let an app change the screen brightness.",
        "It lets the app change system settings such as brightness. It cannot read your files, messages or anything on screen.",
        "Settings > Apps > Special access > Modify system settings.",
    ),
    DND_ACCESS(
        "Do not disturb access", AccessKind.SPECIAL, Manifest.permission.ACCESS_NOTIFICATION_POLICY, true,
        "Lets you say \"put the phone on silent\". Without it, Android allows vibrate at most.",
        "It lets the app switch the ringer between normal, vibrate and silent. It does not read any notification.",
        "Settings > Apps > Special access > Do Not Disturb access.",
    ),
    NOTIFICATION_ACCESS(
        "Read notifications", AccessKind.SPECIAL, null, true,
        "Lets you say \"read my notifications\" or \"clear my notifications\".",
        "The title and text of the notifications showing now, only when you ask. It skips ongoing ones (music, calls), notifications you marked secret, " +
            "and banking, payment and password apps. What it reads is shown to you and spoken, not saved and not sent to an AI. " +
            "If Android greys the switch out, use \"Allow restricted settings\" on this app's page in Settings, as above.",
        "Settings > Notifications > Device and app notifications > JARVIS 3.0 > off.",
    ),
}

/** Reads the real state from Android and builds the intents that ask for access. Nothing here is simulated. */
object AccessChecks {

    fun state(context: Context, key: AccessKey): AccessState = when (key) {
        AccessKey.NOTIFICATIONS ->
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.MICROPHONE, AccessKey.CONTACTS, AccessKey.PHONE_CALLS, AccessKey.ANSWER_CALLS, AccessKey.BLUETOOTH ->
            if (granted(context, key.manifestPermission.orEmpty())) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.BATTERY -> {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isIgnoringBatteryOptimizations(context.packageName)) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.EXACT_ALARMS -> {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (alarms.canScheduleExactAlarms()) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.OVERLAY ->
            if (Settings.canDrawOverlays(context)) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.USAGE_ACCESS -> {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            if (mode == AppOpsManager.MODE_ALLOWED) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.WRITE_SETTINGS ->
            if (Settings.System.canWrite(context)) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.DND_ACCESS -> {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (manager.isNotificationPolicyAccessGranted) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.NOTIFICATION_ACCESS ->
            if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.ACCESSIBILITY -> if (accessibilityEnabled(context)) AccessState.ALLOWED else AccessState.NOT_ALLOWED
    }

    /** True when the person has switched the accessibility service on in Settings (Android lists it in a colon-separated setting). */
    fun accessibilityEnabled(context: Context): Boolean {
        val mine = ComponentName(context, JarvisAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == mine }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The runtime permission to put in Android's dialog, or null when no dialog is needed or possible.
     */
    fun runtimePermission(key: AccessKey): String? = if (key.kind != AccessKind.RUNTIME) null else key.manifestPermission

    /** The Settings screen for a special access, or null when this Android version has none. */
    fun settingsIntent(context: Context, key: AccessKey): Intent? {
        val packageUri = "package:${context.packageName}".toUri()
        return when (key) {
            AccessKey.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            AccessKey.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            AccessKey.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            AccessKey.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AccessKey.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)
            AccessKey.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            AccessKey.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AccessKey.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            AccessKey.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> null
        }
    }

    /** This app's own page in Settings, where any permission can be changed. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    // Android does not say whether a denied permission can still be asked for. After two denials the dialog is silently
    // skipped, so the app remembers that it has asked and, if Android no longer wants to explain, sends the user to Settings.

    fun wasAsked(context: Context, key: AccessKey): Boolean = prefs(context).getBoolean(key.name, false)

    fun markAsked(context: Context, key: AccessKey) {
        prefs(context).edit { putBoolean(key.name, true) }
    }

    /** True when asking again would show nothing, so the only route is the app's Settings page. */
    fun isBlocked(context: Context, key: AccessKey): Boolean {
        val permission = runtimePermission(key) ?: return false
        val activity = context.findActivity() ?: return false
        return wasAsked(context, key) &&
            !granted(context, permission) &&
            !activity.shouldShowRequestPermissionRationale(permission)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("access_requests", Context.MODE_PRIVATE)

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
