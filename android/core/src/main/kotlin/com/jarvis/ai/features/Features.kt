package com.jarvis.ai.features

import com.jarvis.ai.models.Amount

enum class FeatureId {
    AI_ASSISTANT, LOCAL_AI_LAB, CLOUD_AI, VOICE_ASSISTANT, HANDS_FREE, NOTIFICATION_INTELLIGENCE,
    DEVICE_CONTROL, APPLICATION_LAUNCHER, PHONE_ASSISTANT, WHATSAPP_ASSISTANT, SMS_ASSISTANT,
    MOBILE_DATA_MONITOR, AUTOMATION_STUDIO, FILE_MANAGER, DOCUMENT_INTELLIGENCE, RESEARCH_WEB,
    AI_MEMORY, SMART_PLANNER, OFFLINE_KNOWLEDGE, CUSTOM_AGENT_CREATOR, SECURITY_CENTER,
    OFFLINE_PREPARATION_CENTER, PERSONAL_ROUTINES, MULTILINGUAL_ASSISTANT, ADAPTIVE_DASHBOARD, BLUETOOTH_COMPANION,
}

/** Shared building blocks. A capability needed by several features is counted once. */
enum class Capability(val label: String) {
    AI_ROUTER("AI router"),
    LOCAL_RUNTIME("On-device inference runtime"),
    LOCAL_MODEL("Downloaded local model"),
    SPEECH_RECOGNITION("Speech recognition"),
    TEXT_TO_SPEECH("Text-to-speech"),
    AUDIO_SYSTEM("Audio system"),
    LOCAL_DATABASE("Local database"),
    AGENT_REGISTRY("Agent registry"),
    CAPABILITY_REGISTRY("Capability registry"),
    STORAGE_ACCESS_FRAMEWORK("Android file picker"),
}

enum class HardwareNeed { MICROPHONE, SPEECH_RECOGNITION, TEXT_TO_SPEECH, AUDIO_OUTPUT }

enum class InternetNeed { NONE, OPTIONAL, REQUIRED }

enum class OfflineSupport { FULL, PARTIAL, NONE }

/** Whether the feature exists in the app today. A feature that is not built is listed but cannot be selected. */
sealed interface Readiness {
    data object Available : Readiness
    data class Planned(val phase: Int) : Readiness
}

data class PermissionNeed(val permission: PermissionId, val required: Boolean)

data class Feature(
    val id: FeatureId,
    val title: String,
    val description: String,
    val readiness: Readiness,
    val internet: InternetNeed,
    val offline: OfflineSupport,
    val permissions: List<PermissionNeed> = emptyList(),
    val requires: Set<FeatureId> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val hardware: Set<HardwareNeed> = emptySet(),
    /** Download beyond the models. [Amount.NONE] means the feature adds nothing to download. */
    val extraDownload: Amount = Amount.NONE,
    val extraStorage: Amount = Amount.NONE,
    val ramNote: String? = null,
) {
    val selectable: Boolean get() = readiness is Readiness.Available
}

private fun req(p: PermissionId) = PermissionNeed(p, true)
private fun opt(p: PermissionId) = PermissionNeed(p, false)

object FeatureCatalog {
    val all: List<Feature> = listOf(
        Feature(
            FeatureId.AI_ASSISTANT, "AI Assistant",
            "Chat that plans your request into steps and runs the phone tools you have enabled. It needs a cloud provider key until an on-device model can answer.",
            Readiness.Available, InternetNeed.REQUIRED, OfflineSupport.NONE,
            capabilities = setOf(Capability.AI_ROUTER),
        ),
        Feature(
            FeatureId.LOCAL_AI_LAB, "Local AI Lab",
            "Install, test and manage the on-device Gemma models.",
            Readiness.Planned(4), InternetNeed.OPTIONAL, OfflineSupport.FULL,
            capabilities = setOf(Capability.LOCAL_RUNTIME, Capability.LOCAL_MODEL),
            ramNote = "Depends on the model you install.",
        ),
        Feature(
            FeatureId.CLOUD_AI, "Cloud AI Integration",
            "Use your own keys for Gemini, Groq, OpenRouter, OpenAI, Claude or Mistral, or your own https endpoint, tried in the order you choose.",
            Readiness.Available, InternetNeed.REQUIRED, OfflineSupport.NONE,
        ),
        Feature(
            FeatureId.VOICE_ASSISTANT, "Voice Assistant",
            "Speak commands and hear replies, with the mic button and Android's speech recogniser. Replies are spoken when you spoke. Hands-free adds the wake word.",
            Readiness.Available, InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            permissions = listOf(req(PermissionId.RECORD_AUDIO)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.SPEECH_RECOGNITION, Capability.TEXT_TO_SPEECH, Capability.AUDIO_SYSTEM),
            hardware = setOf(HardwareNeed.MICROPHONE, HardwareNeed.SPEECH_RECOGNITION, HardwareNeed.TEXT_TO_SPEECH),
        ),
        Feature(
            FeatureId.HANDS_FREE, "Hands-Free Assistant",
            "Say \"Jarvis\" to wake it without tapping the mic. A visible notification stays on while it listens. The offline listener needs no key (a one-time ~40 MB download); Picovoice is the alternative. Android does not let a microphone service start by itself, so after a restart you switch it on again.",
            Readiness.Available, InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            permissions = listOf(
                req(PermissionId.RECORD_AUDIO), req(PermissionId.POST_NOTIFICATIONS),
                req(PermissionId.FOREGROUND_SERVICE_MICROPHONE), opt(PermissionId.OVERLAY),
            ),
            requires = setOf(FeatureId.VOICE_ASSISTANT),
            capabilities = setOf(Capability.AUDIO_SYSTEM),
            hardware = setOf(HardwareNeed.MICROPHONE, HardwareNeed.AUDIO_OUTPUT),
        ),
        Feature(
            FeatureId.NOTIFICATION_INTELLIGENCE, "Notification Intelligence",
            "Read out or dismiss the notifications showing now, by voice or text. Notifications are not summarised by an AI and are never sent to one. Banking and payment apps are left out.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.NOTIFICATION_LISTENER), opt(PermissionId.POST_NOTIFICATIONS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.DEVICE_CONTROL, "Device Control",
            "Flashlight, set an alarm through your Clock app, open a link, open YouTube, show device information, the time and date. With Accessibility switched on: read the screen aloud, scroll, and press Back, Home, Recents, lock or screenshot. " +
                "Tapping and typing inside other apps exist but stay off until you switch them on in Prefs > Access. Android does not allow apps to switch Wi-Fi or Bluetooth silently, so those will only open the right settings screen.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.SET_ALARM), opt(PermissionId.ACCESSIBILITY)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.APPLICATION_LAUNCHER, "Application Launcher",
            "Open an installed app by name, typed or spoken. It only opens the app; it cannot tap inside it.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.OVERLAY)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.PHONE_ASSISTANT, "Phone Assistant",
            "Find a contact and start a call. A call you speak is read back and goes ahead after a 3-second countdown you can cancel; typed or AI-planned calls need a tap.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.READ_CONTACTS), opt(PermissionId.CALL_PHONE)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.WHATSAPP_ASSISTANT, "WhatsApp Assistant",
            "Count unread chats by opening WhatsApp and reading its chat list, read new messages from notifications, and prepare a message by voice. " +
                "Pressing Send for you is off unless you switch it on; Android never confirms that a message was sent.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.PARTIAL,
            permissions = listOf(opt(PermissionId.READ_CONTACTS), opt(PermissionId.NOTIFICATION_LISTENER), opt(PermissionId.ACCESSIBILITY)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.SMS_ASSISTANT, "SMS Assistant",
            "Prepare a text message and open your messaging app with it. It never sends silently.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.READ_CONTACTS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.MOBILE_DATA_MONITOR, "Mobile Data Monitor",
            "See how much mobile data the phone and individual apps have used today, this week and this month. Needs Usage access.",
            Readiness.Planned(5), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.USAGE_ACCESS)),
        ),
        Feature(
            FeatureId.AUTOMATION_STUDIO, "Automation Studio",
            "Routines that run on a schedule (daily, weekly or every few minutes) and can only add a task, add a note or show a notification. " +
                "To change one, delete it and make it again.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.POST_NOTIFICATIONS), opt(PermissionId.EXACT_ALARMS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.FILE_MANAGER, "File Manager",
            "Browse a folder you pick with the Android file picker, make folders, rename, delete after a confirmation, and sort a folder's files by type after a preview. No all-files access is requested.",
            Readiness.Planned(5), InternetNeed.NONE, OfflineSupport.FULL,
            capabilities = setOf(Capability.STORAGE_ACCESS_FRAMEWORK),
        ),
        Feature(
            FeatureId.DOCUMENT_INTELLIGENCE, "Document Intelligence",
            "Import text, Markdown, CSV, HTML, Word (.docx) or PDF files and search them on the phone. Asking an AI about a document is a separate button and sends only the matching passages to your cloud provider.",
            Readiness.Planned(6), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.FILE_MANAGER, FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.RESEARCH_WEB, "Research and Web",
            "Weather, Wikipedia and a web-grounded answer (through your Gemini key). Web content is treated as untrusted and cannot approve any action.",
            Readiness.Available, InternetNeed.REQUIRED, OfflineSupport.NONE,
            requires = setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI),
        ),
        Feature(
            FeatureId.AI_MEMORY, "AI Memory",
            "Remembers what you add or ask it to (\"remember that ...\"), stored only on this phone. You can delete any of it.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.SMART_PLANNER, "Smart Planner",
            "Tasks, notes and reminders, with a daily overview.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.POST_NOTIFICATIONS), opt(PermissionId.EXACT_ALARMS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.OFFLINE_KNOWLEDGE, "Offline Knowledge",
            "Download or import a Wikipedia file (Kiwix .zim) once, then look things up with no internet.",
            Readiness.Available, InternetNeed.OPTIONAL, OfflineSupport.FULL,
            capabilities = setOf(Capability.LOCAL_DATABASE),
            extraDownload = Amount.Unknown("Chosen later in the Offline Preparation Center"),
            extraStorage = Amount.Unknown("Chosen later in the Offline Preparation Center"),
        ),
        Feature(
            FeatureId.CUSTOM_AGENT_CREATOR, "Custom Agent Creator",
            "Describe an assistant in plain language and build it from approved low-risk tools, with no code. Nothing runs until you approve it.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.AI_ROUTER, Capability.AGENT_REGISTRY, Capability.CAPABILITY_REGISTRY, Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.SECURITY_CENTER, "Security Center",
            "Permission review, key status and a list of what the review did not check. The activity log is in the Logs tab.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
        ),
        Feature(
            FeatureId.OFFLINE_PREPARATION_CENTER, "Offline Preparation Center",
            "Shows what works without internet and runs a storage self-test. AI chat without internet needs the on-device engine.",
            Readiness.Available, InternetNeed.OPTIONAL, OfflineSupport.FULL,
        ),
        Feature(
            FeatureId.PERSONAL_ROUTINES, "Personal Routines",
            "Morning and evening routines built from your own steps: a reminder at the time you choose, and Jarvis reads the steps to you when you ask.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AUTOMATION_STUDIO),
        ),
        Feature(
            FeatureId.MULTILINGUAL_ASSISTANT, "Multilingual Assistant",
            "Choose English or Hindi for the app. Jarvis listens for that language and answers in the language you speak or type (English, Hindi or Hinglish). The phone's own speech engine must support Hindi.",
            Readiness.Available, InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.ADAPTIVE_DASHBOARD, "Adaptive Dashboard",
            "A home screen with shortcuts to the things you ask for most, counted on this phone only.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
        ),
        Feature(
            FeatureId.BLUETOOTH_COMPANION, "Bluetooth Companion",
            "When a Bluetooth audio device connects (earbuds, car), Jarvis can say so, read new notifications aloud, and put up a one-tap button to start hands-free listening. " +
                "Android does not allow a microphone service to start by itself, so listening starts with that one tap.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.BLUETOOTH_CONNECT), opt(PermissionId.NOTIFICATION_LISTENER), opt(PermissionId.POST_NOTIFICATIONS)),
            requires = setOf(FeatureId.VOICE_ASSISTANT),
            capabilities = setOf(Capability.TEXT_TO_SPEECH, Capability.AUDIO_SYSTEM),
            hardware = setOf(HardwareNeed.AUDIO_OUTPUT),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun feature(id: FeatureId): Feature = byId.getValue(id)
}
