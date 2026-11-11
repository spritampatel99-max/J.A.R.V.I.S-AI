package com.jarvis.ai.android.device

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

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
        "Settings > Apps > J.A.R.V.I.S. > Notifications.",
    ),
    BATTERY(
        "Run in the background", AccessKind.SPECIAL, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true,
        "Stops Android pausing a long model download when the screen turns off.",
        "Nothing is read. It only exempts the app from battery saving, which uses more battery while it works.",
        "Settings > Apps > Special access > Battery optimisation.",
    ),
    MICROPHONE(
        "Microphone", AccessKind.RUNTIME, Manifest.permission.RECORD_AUDIO, true,
        "For voice commands. It listens only while you hold a listening session open with the mic button; there is no always-listening mode.",
        "Sound is passed to Android's speech recogniser for that one request. The app does not record or store audio. " +
            "Depending on your phone, the recogniser may send the sound to its provider (usually Google) to turn it into text; " +
            "on Android 13 and later the app asks for the on-device recogniser when your phone has one.",
        "Settings > Apps > J.A.R.V.I.S. > Permissions > Microphone.",
    ),
    CONTACTS(
        "Contacts", AccessKind.RUNTIME, Manifest.permission.READ_CONTACTS, true,
        "To find a person by name when you ask to call them.",
        "Names and phone numbers matching the name you asked for, only when a command needs one. Contacts are never uploaded by the app, " +
            "and the confirmation shows the exact name and number before anything is dialled.",
        "Settings > Apps > J.A.R.V.I.S. > Permissions > Contacts.",
    ),
    PHONE_CALLS(
        "Phone calls", AccessKind.RUNTIME, Manifest.permission.CALL_PHONE, true,
        "To place a call you asked for, after you tap Confirm. Without it, the phone's dialer opens with the number filled in and you press call yourself.",
        "It lets the app start a call. It does not read your call history. Emergency numbers are never dialled by the app.",
        "Settings > Apps > J.A.R.V.I.S. > Permissions > Phone.",
    ),
    ANSWER_CALLS(
        "Answer and end calls", AccessKind.RUNTIME, Manifest.permission.ANSWER_PHONE_CALLS, true,
        "So you can say or type \"answer the call\" or \"hang up\". The app cannot see who is calling and does not watch for calls.",
        "It lets the app ask Android to pick up a ringing call or end the current one, only when you ask.",
        "Settings > Apps > J.A.R.V.I.S. > Permissions > Phone.",
    ),
    EXACT_ALARMS(
        "Exact alarms and reminders", AccessKind.SPECIAL, Manifest.permission.SCHEDULE_EXACT_ALARM, true,
        "Lets reminders and automations fire at the exact minute. Without it they still run, but Android may deliver them a few minutes late.",
        "Nothing is read. It only lets the app schedule precise timers.",
        "Settings > Apps > Special access > Alarms & reminders.",
    ),
    USAGE_ACCESS(
        "Usage access", AccessKind.SPECIAL, Manifest.permission.PACKAGE_USAGE_STATS, false,
        "For a data and screen-time monitor. That monitor is not built yet.",
        "Which apps you use and for how long. Powerful, so leave it off until the monitor exists unless you want it.",
        "Settings > Apps > Special access > Usage access.",
    ),
    NOTIFICATION_ACCESS(
        "Read notifications", AccessKind.UNAVAILABLE, null, false,
        "For summarising your notifications. Android lists an app on its switch page only after the app has a notification " +
            "service, which is not built yet, so there is nothing to turn on.",
        "Would be the text of notifications from other apps.",
        "Settings > Notifications > Device and app notifications.",
    ),
}

/** Reads the real state from Android and builds the intents that ask for access. Nothing here is simulated. */
object AccessChecks {

    fun state(context: Context, key: AccessKey): AccessState = when (key) {
        AccessKey.NOTIFICATIONS ->
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.MICROPHONE, AccessKey.CONTACTS, AccessKey.PHONE_CALLS, AccessKey.ANSWER_CALLS ->
            if (granted(context, key.manifestPermission.orEmpty())) AccessState.ALLOWED else AccessState.NOT_ALLOWED

        AccessKey.BATTERY -> {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isIgnoringBatteryOptimizations(context.packageName)) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.EXACT_ALARMS ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                AccessState.NOT_NEEDED
            } else {
                val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (alarms.canScheduleExactAlarms()) AccessState.ALLOWED else AccessState.NOT_ALLOWED
            }

        AccessKey.USAGE_ACCESS -> {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            if (mode == AppOpsManager.MODE_ALLOWED) AccessState.ALLOWED else AccessState.NOT_ALLOWED
        }

        AccessKey.NOTIFICATION_ACCESS -> AccessState.NOT_ALLOWED
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The runtime permission to put in Android's dialog, or null when no dialog is needed or possible.
     * Notifications only have a permission dialog on Android 13 and later.
     */
    fun runtimePermission(key: AccessKey): String? = when {
        key.kind != AccessKind.RUNTIME -> null
        key == AccessKey.NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> null
        else -> key.manifestPermission
    }

    /** The Settings screen for a special access, or null when this Android version has none. */
    fun settingsIntent(context: Context, key: AccessKey): Intent? {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (key) {
            AccessKey.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            AccessKey.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else null
            AccessKey.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AccessKey.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> null
        }
    }

    /** This app's own page in Settings, where any permission can be changed. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    // Android does not say whether a denied permission can still be asked for. After two denials the dialog is silently
    // skipped, so the app remembers that it has asked and, if Android no longer wants to explain, sends the user to Settings.

    fun wasAsked(context: Context, key: AccessKey): Boolean = prefs(context).getBoolean(key.name, false)

    fun markAsked(context: Context, key: AccessKey) {
        prefs(context).edit().putBoolean(key.name, true).apply()
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
