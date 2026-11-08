package com.jarvis.ai

import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.device.ConnectionType
import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.device.Reading

/** A made-up phone for tests. Every field can be replaced, and any reading can be made unavailable. */
fun phone(
    abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    totalRam: Long? = 12 * Bytes.GIB,
    availableRam: Long? = 6 * Bytes.GIB,
    lowMemory: Boolean? = false,
    totalStorage: Long? = 256 * Bytes.GIB,
    freeStorage: Long? = 100 * Bytes.GIB,
    online: Boolean? = true,
    connection: ConnectionType? = ConnectionType.WIFI,
    metered: Boolean? = false,
    battery: Int? = 80,
    charging: Boolean? = false,
    microphone: Boolean? = true,
    speech: Boolean? = true,
    tts: Boolean? = true,
    audioOut: Boolean? = true,
): DeviceProfile {
    fun <T : Any> r(value: T?, why: String = "test: not readable"): Reading<T> =
        if (value == null) Reading.unavailable(why) else Reading.measured(value)
    return DeviceProfile(
        manufacturer = "TestCo",
        model = "T-1",
        androidRelease = "14",
        apiLevel = 34,
        supportedAbis = abis,
        cpuCores = r(8),
        totalRamBytes = r(totalRam),
        availableRamBytes = r(availableRam),
        lowMemory = r(lowMemory),
        appMemoryClassMb = r(256),
        largeMemoryClassMb = r(512),
        totalStorageBytes = r(totalStorage),
        freeStorageBytes = r(freeStorage),
        openGlEsVersion = r("3.2"),
        vulkanSupported = r(true),
        cpuInference = Reading.unavailable("Checked when a model is first loaded"),
        gpuAcceleration = Reading.unavailable("Checked when a model is first loaded"),
        npuAcceleration = Reading.unavailable("Checked when a model is first loaded"),
        batteryPercent = r(battery),
        charging = r(charging),
        ignoringBatteryOptimizations = r(false),
        online = r(online),
        connection = r(connection),
        metered = r(metered),
        microphone = r(microphone),
        camera = r(true),
        bluetooth = r(true),
        audioOutput = r(audioOut),
        speechRecognition = r(speech),
        onDeviceSpeechRecognition = r(false),
        textToSpeech = r(tts),
    )
}
