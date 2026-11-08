package com.jarvis.ai.features

import com.jarvis.ai.models.Amount

enum class FeatureId {
    AI_ASSISTANT, LOCAL_AI_LAB, CLOUD_AI, VOICE_ASSISTANT, HANDS_FREE, NOTIFICATION_INTELLIGENCE,
    DEVICE_CONTROL, APPLICATION_LAUNCHER, PHONE_ASSISTANT, WHATSAPP_ASSISTANT, SMS_ASSISTANT,
    MOBILE_DATA_MONITOR, AUTOMATION_STUDIO, FILE_MANAGER, DOCUMENT_INTELLIGENCE, RESEARCH_WEB,
    AI_MEMORY, SMART_PLANNER, OFFLINE_KNOWLEDGE, CUSTOM_AGENT_CREATOR, SECURITY_CENTER,
    OFFLINE_PREPARATION_CENTER, PERSONAL_ROUTINES, MULTILINGUAL_ASSISTANT, ADAPTIVE_DASHBOARD,
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
            "Chat that plans your request into steps and runs the phone tools you have enabled. Today it needs a cloud provider key; " +
                "on-device models arrive in Phase 4.",
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
            "Use your own keys for Gemini, Groq, OpenRouter, OpenAI or Claude, tried in the order you choose.",
            Readiness.Available, InternetNeed.REQUIRED, OfflineSupport.NONE,
        ),
        Feature(
            FeatureId.VOICE_ASSISTANT, "Voice Assistant",
            "Speak commands and hear replies.",
            Readiness.Planned(6), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            permissions = listOf(req(PermissionId.RECORD_AUDIO)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.SPEECH_RECOGNITION, Capability.TEXT_TO_SPEECH, Capability.AUDIO_SYSTEM),
            hardware = setOf(HardwareNeed.MICROPHONE, HardwareNeed.SPEECH_RECOGNITION, HardwareNeed.TEXT_TO_SPEECH),
        ),
        Feature(
            FeatureId.HANDS_FREE, "Hands-Free Assistant",
            "Push-to-talk and user-started voice sessions with a visible microphone indicator.",
            Readiness.Planned(6), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            permissions = listOf(
                req(PermissionId.RECORD_AUDIO), req(PermissionId.POST_NOTIFICATIONS),
                req(PermissionId.FOREGROUND_SERVICE_MICROPHONE), opt(PermissionId.NOTIFICATION_LISTENER),
            ),
            requires = setOf(FeatureId.VOICE_ASSISTANT),
            capabilities = setOf(Capability.AUDIO_SYSTEM),
            hardware = setOf(HardwareNeed.MICROPHONE, HardwareNeed.AUDIO_OUTPUT),
        ),
        Feature(
            FeatureId.NOTIFICATION_INTELLIGENCE, "Notification Intelligence",
            "Summarise and prioritise notifications from the apps you allow.",
            Readiness.Planned(6), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            permissions = listOf(req(PermissionId.NOTIFICATION_LISTENER), opt(PermissionId.POST_NOTIFICATIONS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.DEVICE_CONTROL, "Device Control",
            "Flashlight, set an alarm through your Clock app, open a link, open YouTube, show device information. " +
                "Android does not allow apps to switch Wi-Fi or Bluetooth silently, so those will only open the right settings screen.",
            Readiness.Available, InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.SET_ALARM)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.APPLICATION_LAUNCHER, "Application Launcher",
            "Open apps by name.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.PHONE_ASSISTANT, "Phone Assistant",
            "Find a contact and start a call after you confirm.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.READ_CONTACTS), opt(PermissionId.CALL_PHONE)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.WHATSAPP_ASSISTANT, "WhatsApp Assistant",
            "Prepare a message and open WhatsApp with it. You send it; the app cannot confirm that it was sent.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.PARTIAL,
            permissions = listOf(opt(PermissionId.READ_CONTACTS), opt(PermissionId.NOTIFICATION_LISTENER)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.SMS_ASSISTANT, "SMS Assistant",
            "Prepare a text message and open your messaging app with it. It never sends silently.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.READ_CONTACTS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.MOBILE_DATA_MONITOR, "Mobile Data Monitor",
            "See how much mobile data the phone and individual apps have used.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(req(PermissionId.USAGE_ACCESS)),
        ),
        Feature(
            FeatureId.AUTOMATION_STUDIO, "Automation Studio",
            "Build routines that run on a schedule or a trigger, each confirmed by you.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.POST_NOTIFICATIONS), opt(PermissionId.EXACT_ALARMS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.FILE_MANAGER, "File Manager",
            "Browse and organise files you pick with the Android file picker. No all-files access is requested.",
            Readiness.Planned(6), InternetNeed.NONE, OfflineSupport.FULL,
            capabilities = setOf(Capability.STORAGE_ACCESS_FRAMEWORK),
        ),
        Feature(
            FeatureId.DOCUMENT_INTELLIGENCE, "Document Intelligence",
            "Ask questions about documents you import.",
            Readiness.Planned(7), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.FILE_MANAGER, FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.RESEARCH_WEB, "Research and Web",
            "Look things up on the web. Web content is treated as untrusted and cannot approve any action.",
            Readiness.Planned(6), InternetNeed.REQUIRED, OfflineSupport.NONE,
            requires = setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI),
        ),
        Feature(
            FeatureId.AI_MEMORY, "AI Memory",
            "Remembers facts you approve, stored only on this phone.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.SMART_PLANNER, "Smart Planner",
            "Tasks, reminders and plans.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
            permissions = listOf(opt(PermissionId.POST_NOTIFICATIONS), opt(PermissionId.EXACT_ALARMS)),
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.OFFLINE_KNOWLEDGE, "Offline Knowledge",
            "Downloadable reference packs that work without internet.",
            Readiness.Planned(8), InternetNeed.OPTIONAL, OfflineSupport.FULL,
            requires = setOf(FeatureId.LOCAL_AI_LAB),
            capabilities = setOf(Capability.LOCAL_DATABASE),
            extraDownload = Amount.Unknown("Chosen later in the Offline Preparation Center"),
            extraStorage = Amount.Unknown("Chosen later in the Offline Preparation Center"),
        ),
        Feature(
            FeatureId.CUSTOM_AGENT_CREATOR, "Custom Agent Creator",
            "Describe an assistant in plain language and build it from approved tools, with no code.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.AI_ASSISTANT),
            capabilities = setOf(Capability.AI_ROUTER, Capability.AGENT_REGISTRY, Capability.CAPABILITY_REGISTRY, Capability.LOCAL_DATABASE),
        ),
        Feature(
            FeatureId.SECURITY_CENTER, "Security Center",
            "Permission review, key status and the activity log in one place. An activity log already exists in the Activity tab.",
            Readiness.Planned(9), InternetNeed.NONE, OfflineSupport.FULL,
        ),
        Feature(
            FeatureId.OFFLINE_PREPARATION_CENTER, "Offline Preparation Center",
            "Check what works offline and prepare what does not.",
            Readiness.Planned(8), InternetNeed.OPTIONAL, OfflineSupport.FULL,
            requires = setOf(FeatureId.LOCAL_AI_LAB),
        ),
        Feature(
            FeatureId.PERSONAL_ROUTINES, "Personal Routines",
            "Morning and evening routines built from your own steps.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AUTOMATION_STUDIO),
        ),
        Feature(
            FeatureId.MULTILINGUAL_ASSISTANT, "Multilingual Assistant",
            "Answer in the language you choose.",
            Readiness.Planned(6), InternetNeed.OPTIONAL, OfflineSupport.PARTIAL,
            requires = setOf(FeatureId.AI_ASSISTANT),
        ),
        Feature(
            FeatureId.ADAPTIVE_DASHBOARD, "Adaptive Dashboard",
            "A home screen built from the features you actually use.",
            Readiness.Planned(7), InternetNeed.NONE, OfflineSupport.FULL,
            requires = setOf(FeatureId.AI_MEMORY),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun feature(id: FeatureId): Feature = byId.getValue(id)
}
