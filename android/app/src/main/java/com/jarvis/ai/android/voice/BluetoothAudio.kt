package com.jarvis.ai.android.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Whether sound from this phone is going to a Bluetooth headset, speaker or hearing aid right now. Read from Android's own list of
 * audio outputs, which needs no permission. This is what "Bluetooth is connected" means for reading notifications aloud: a Bluetooth
 * device that is not an audio device (a watch, a keyboard) does not count.
 */
object BluetoothAudio {
    private val TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** The connected Bluetooth audio output, or null when none is connected. */
    fun connected(context: Context): AudioDeviceInfo? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in TYPES }
    }

    /** The device's name as Android reports it, or null when it gives none. */
    fun name(device: AudioDeviceInfo): String? = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
