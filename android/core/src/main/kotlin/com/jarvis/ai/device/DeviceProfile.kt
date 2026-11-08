package com.jarvis.ai.device

/** How a value was obtained. The UI shows it next to every figure so measurements are never mistaken for guesses. */
enum class Provenance { MEASURED, ESTIMATED, UNAVAILABLE }

/**
 * One reading from the device. A reading is either a value with a provenance, or
 * [Provenance.UNAVAILABLE] with a reason. A missing value is never replaced by a made-up default.
 */
data class Reading<out T : Any>(
    val value: T?,
    val provenance: Provenance,
    val note: String? = null,
) {
    init {
        require((provenance == Provenance.UNAVAILABLE) == (value == null)) {
            "A reading has a value exactly when it is not UNAVAILABLE"
        }
    }

    val isKnown: Boolean get() = value != null

    companion object {
        fun <T : Any> measured(value: T): Reading<T> = Reading(value, Provenance.MEASURED)
        fun <T : Any> estimated(value: T, note: String): Reading<T> = Reading(value, Provenance.ESTIMATED, note)
        fun <T : Any> unavailable(note: String): Reading<T> = Reading(null, Provenance.UNAVAILABLE, note)
    }
}

enum class ConnectionType { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

/**
 * What the phone reported about itself during setup (Part 6 of the product prompt). Only facts
 * the setup needs are collected: no identifiers, account names, contacts or installed-app lists.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,

    val cpuCores: Reading<Int>,

    val totalRamBytes: Reading<Long>,
    val availableRamBytes: Reading<Long>,
    val lowMemory: Reading<Boolean>,
    val appMemoryClassMb: Reading<Int>,
    val largeMemoryClassMb: Reading<Int>,

    val totalStorageBytes: Reading<Long>,
    val freeStorageBytes: Reading<Long>,

    val openGlEsVersion: Reading<String>,
    val vulkanSupported: Reading<Boolean>,

    /** AI back-ends can only be verified by loading a model, so these stay unavailable until then. */
    val cpuInference: Reading<Boolean>,
    val gpuAcceleration: Reading<Boolean>,
    val npuAcceleration: Reading<Boolean>,

    val batteryPercent: Reading<Int>,
    val charging: Reading<Boolean>,
    val ignoringBatteryOptimizations: Reading<Boolean>,

    val online: Reading<Boolean>,
    val connection: Reading<ConnectionType>,
    val metered: Reading<Boolean>,

    val microphone: Reading<Boolean>,
    val camera: Reading<Boolean>,
    val bluetooth: Reading<Boolean>,
    val audioOutput: Reading<Boolean>,
    val speechRecognition: Reading<Boolean>,
    val onDeviceSpeechRecognition: Reading<Boolean>,
    val textToSpeech: Reading<Boolean>,
) {
    val supportsArm64: Boolean get() = ARM64 in supportedAbis

    companion object {
        const val ARM64 = "arm64-v8a"
    }
}

/** Port implemented in the Android module. */
interface DeviceAnalyzer {
    /** Reads the current state of the phone. It must not throw for an unreadable value; it reports it as unavailable. */
    suspend fun analyze(): DeviceProfile
}
