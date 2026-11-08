package com.jarvis.ai.features

enum class PermissionKind {
    /** Granted when the app is installed; shown to the user on the install screen. */
    INSTALL_TIME,

    /** A normal Android permission dialog, shown only when the user turns on the feature that needs it. */
    RUNTIME,

    /** A system settings screen the user must open and switch on. The app can never grant it itself. */
    SPECIAL_ACCESS,
}

enum class PermissionId { INTERNET, ACCESS_NETWORK_STATE, SET_ALARM, RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE_MICROPHONE, NOTIFICATION_LISTENER, READ_CONTACTS, CALL_PHONE, USAGE_ACCESS, EXACT_ALARMS }

/** The five things Part 25 says to explain for every permission. */
data class PermissionInfo(
    val id: PermissionId,
    val title: String,
    val kind: PermissionKind,
    val purpose: String,
    val informationAccessed: String,
    val howToRevoke: String,
)

object PermissionCatalog {
    private const val APP_SETTINGS = "Settings > Apps > J.A.R.V.I.S.-AI > Permissions (the exact menu names vary by manufacturer)."

    val all: List<PermissionInfo> = listOf(
        PermissionInfo(
            PermissionId.INTERNET, "Internet", PermissionKind.INSTALL_TIME,
            "Lets the app talk to the cloud AI providers you add, and download models you choose.",
            "Nothing on the phone. It only allows network connections.",
            "Cannot be switched off for an app on its own; switch off airplane mode or remove the provider keys to stop all traffic.",
        ),
        PermissionInfo(
            PermissionId.ACCESS_NETWORK_STATE, "Network state", PermissionKind.INSTALL_TIME,
            "Lets the app tell whether the phone is online and whether the connection is Wi-Fi or mobile data.",
            "Whether you are online and the type of connection. No network names or addresses.",
            "Install-time permission; it is removed by uninstalling the app.",
        ),
        PermissionInfo(
            PermissionId.SET_ALARM, "Set alarms", PermissionKind.INSTALL_TIME,
            "Lets the app hand an alarm to the system Clock app when you ask for one.",
            "Nothing is read. The alarm is created by your Clock app.",
            "Install-time permission; it is removed by uninstalling the app.",
        ),
        PermissionInfo(
            PermissionId.RECORD_AUDIO, "Microphone", PermissionKind.RUNTIME,
            "Voice commands and conversations.",
            "Audio from the microphone, only while a voice session you started is active.",
            APP_SETTINGS,
        ),
        PermissionInfo(
            PermissionId.POST_NOTIFICATIONS, "Show notifications", PermissionKind.RUNTIME,
            "Reminders, and the visible notice Android requires while a hands-free session runs.",
            "Nothing is read; the app only shows its own notifications.",
            APP_SETTINGS,
        ),
        PermissionInfo(
            PermissionId.FOREGROUND_SERVICE_MICROPHONE, "Microphone while running in the foreground", PermissionKind.INSTALL_TIME,
            "Lets a hands-free voice session you started keep listening with a visible notification.",
            "Audio, only during that session.",
            "Install-time permission; stop the session from its notification, or uninstall the app.",
        ),
        PermissionInfo(
            PermissionId.NOTIFICATION_LISTENER, "Notification access", PermissionKind.SPECIAL_ACCESS,
            "Reading and summarising notifications from the apps you allow.",
            "The title and text of notifications that other apps post. Message notifications can contain private conversations.",
            "Settings > Notifications > Device & app notifications > J.A.R.V.I.S.-AI (the location varies by manufacturer).",
        ),
        PermissionInfo(
            PermissionId.READ_CONTACTS, "Contacts", PermissionKind.RUNTIME,
            "Finding the person you ask to call or message.",
            "Contact names and numbers, read on demand.",
            APP_SETTINGS,
        ),
        PermissionInfo(
            PermissionId.CALL_PHONE, "Start phone calls", PermissionKind.RUNTIME,
            "Placing a call directly after you confirm it. Without it the app can only open the dialler with the number filled in.",
            "Nothing is read. It allows starting a call.",
            APP_SETTINGS,
        ),
        PermissionInfo(
            PermissionId.USAGE_ACCESS, "Usage access", PermissionKind.SPECIAL_ACCESS,
            "Showing which apps used mobile data.",
            "Per-app usage statistics. Without it only whole-phone totals are possible.",
            "Settings > Apps > Special app access > Usage access (the location varies by manufacturer).",
        ),
        PermissionInfo(
            PermissionId.EXACT_ALARMS, "Exact alarms", PermissionKind.SPECIAL_ACCESS,
            "Reminders that fire at the exact minute. Without it reminders may be a few minutes late.",
            "Nothing is read.",
            "Settings > Apps > Special app access > Alarms & reminders (the location varies by manufacturer).",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun info(id: PermissionId): PermissionInfo = byId.getValue(id)
}
