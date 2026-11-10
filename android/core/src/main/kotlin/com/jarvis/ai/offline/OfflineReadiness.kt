package com.jarvis.ai.offline

enum class Connectivity { ONLINE, OFFLINE, UNKNOWN }

enum class CapabilityState(val label: String) {
    WORKS("Works"),
    LIMITED("Limited"),
    UNAVAILABLE("Not available right now"),
    NOT_BUILT("Not built yet"),
}

data class Capability(val id: String, val name: String, val state: CapabilityState, val detail: String)

/** What the phone and the app can do right now. Each field comes from a real check made by the app, never from an assumption. */
data class OfflineSnapshot(
    val connectivity: Connectivity,
    /** True only when an on-device inference engine is part of this build. */
    val localEngineBuilt: Boolean,
    val installedLocalModels: List<String>,
    /** True when the phone's speech recogniser can work without internet, false when it needs it, null when Android did not say. */
    val offlineSpeechRecognition: Boolean?,
    val speechRecognitionAvailable: Boolean,
    val textToSpeechAvailable: Boolean,
    val cloudKeyCount: Int,
    val notificationsAllowed: Boolean,
    /** Results of [OfflineSelfTest]; null if it has not been run. */
    val selfTest: List<SelfTestResult>?,
)

data class OfflineReport(
    val headline: String,
    val capabilities: List<Capability>,
    /** True when an AI answer can be produced right now, locally or through a cloud provider. */
    val aiAnswersPossible: Boolean,
)

/**
 * Says plainly what works without internet. The local AI engine is not part of this build, so without internet there is no AI chat, and
 * this report says that rather than implying otherwise. Built-in commands, the planner, reminders and automations do not need internet.
 */
object OfflineReadiness {

    fun assess(s: OfflineSnapshot): OfflineReport {
        val caps = mutableListOf<Capability>()
        val localAi = s.localEngineBuilt && s.installedLocalModels.isNotEmpty()
        val cloudAi = s.cloudKeyCount > 0 && s.connectivity != Connectivity.OFFLINE

        caps += Capability(
            "commands", "Built-in commands",
            CapabilityState.WORKS,
            "Flashlight, alarms, volume, opening apps and calls are matched on the phone and need no internet. Each still needs its Android permission.",
        )

        val planner = s.selfTest?.firstOrNull { it.id == "planner" }
        caps += Capability(
            "planner", "Planner, notes and reminders",
            when (planner?.passed) { true -> CapabilityState.WORKS; false -> CapabilityState.UNAVAILABLE; null -> CapabilityState.WORKS },
            when (planner?.passed) {
                true -> "Checked just now: saved and read back from the phone's storage."
                false -> "The storage check failed: ${planner.detail}"
                null -> "Kept in a private file on the phone. Run the check to confirm it saves correctly."
            },
        )
        val memory = s.selfTest?.firstOrNull { it.id == "memory" }
        caps += Capability(
            "memory", "Memory",
            when (memory?.passed) { true -> CapabilityState.WORKS; false -> CapabilityState.UNAVAILABLE; null -> CapabilityState.WORKS },
            when (memory?.passed) {
                true -> "Checked just now: saved and read back from the phone's storage."
                false -> "The storage check failed: ${memory.detail}"
                null -> "Kept in a private file on the phone."
            },
        )
        caps += Capability(
            "automations", "Reminders and automations",
            if (s.notificationsAllowed) CapabilityState.WORKS else CapabilityState.LIMITED,
            if (s.notificationsAllowed) "They run on the phone without internet. Android decides the exact moment, so a run can be a little late."
            else "Android notifications are off, so reminders and notification automations cannot be shown. Turn them on in Prefs > Manage access.",
        )

        caps += Capability(
            "local_ai", "AI chat without internet",
            when {
                !s.localEngineBuilt -> CapabilityState.NOT_BUILT
                s.installedLocalModels.isEmpty() -> CapabilityState.UNAVAILABLE
                else -> CapabilityState.WORKS
            },
            when {
                !s.localEngineBuilt -> "The on-device engine is not built in this version, so a downloaded model cannot answer yet. AI chat needs internet and a cloud key."
                s.installedLocalModels.isEmpty() -> "No local model is installed. Download one in Prefs > Models."
                else -> "Answers on this phone with ${s.installedLocalModels.joinToString(", ")}."
            },
        )
        caps += Capability(
            "cloud_ai", "AI chat with a cloud provider",
            when {
                s.cloudKeyCount == 0 -> CapabilityState.UNAVAILABLE
                s.connectivity == Connectivity.OFFLINE -> CapabilityState.UNAVAILABLE
                s.connectivity == Connectivity.UNKNOWN -> CapabilityState.LIMITED
                else -> CapabilityState.WORKS
            },
            when {
                s.cloudKeyCount == 0 -> "No cloud key is saved. Add one in Prefs > Cloud AI."
                s.connectivity == Connectivity.OFFLINE -> "You are offline."
                s.connectivity == Connectivity.UNKNOWN -> "Android did not say whether you are online. It will be tried when you send."
                else -> "${s.cloudKeyCount} provider(s) set up. Your messages are sent to the provider that answers."
            },
        )

        caps += Capability(
            "voice_in", "Voice input",
            when {
                !s.speechRecognitionAvailable -> CapabilityState.UNAVAILABLE
                s.offlineSpeechRecognition == true -> CapabilityState.WORKS
                else -> CapabilityState.LIMITED
            },
            when {
                !s.speechRecognitionAvailable -> "This phone has no speech recogniser."
                s.offlineSpeechRecognition == true -> "The phone's recogniser can work without internet."
                s.offlineSpeechRecognition == false -> "The phone's recogniser needs internet. An offline language pack can be added in Android's settings."
                else -> "Android did not say whether the recogniser works without internet."
            },
        )
        caps += Capability(
            "voice_out", "Spoken replies",
            if (s.textToSpeechAvailable) CapabilityState.WORKS else CapabilityState.UNAVAILABLE,
            if (s.textToSpeechAvailable) "Spoken by the phone's own voice." else "No text-to-speech voice is available on this phone.",
        )
        caps += Capability("web", "Web research", CapabilityState.NOT_BUILT, "Not built in this version. The app never claims to have looked something up.")
        caps += Capability("knowledge", "Search your own documents", CapabilityState.NOT_BUILT, "Not built in this version.")

        val ai = localAi || cloudAi
        val headline = when {
            localAi -> "AI answers can run on this phone."
            s.connectivity == Connectivity.OFFLINE ->
                "You are offline. Built-in commands, planner, reminders and automations still work. AI chat needs internet in this version."
            cloudAi -> "You are online and a cloud provider is ready. Without internet, built-in commands, planner and reminders still work."
            else -> "No AI provider is ready. Built-in commands, planner and reminders work. Add a cloud key to chat."
        }
        return OfflineReport(headline, caps, ai)
    }

    /** What chat says when a message needs an AI answer and none can be produced. */
    fun aiUnavailableMessage(s: OfflineSnapshot): String = when {
        s.localEngineBuilt && s.installedLocalModels.isNotEmpty() -> "The local model could not answer. Try again."
        s.connectivity == Connectivity.OFFLINE && s.cloudKeyCount > 0 ->
            "You are offline, and the on-device engine is not available in this version, so I cannot answer that with AI. Built-in commands still work."
        s.cloudKeyCount == 0 -> "No AI provider is set up. Add a cloud key in Prefs > Cloud AI. Built-in commands still work without one."
        else -> "I could not reach an AI provider. Built-in commands still work."
    }
}
