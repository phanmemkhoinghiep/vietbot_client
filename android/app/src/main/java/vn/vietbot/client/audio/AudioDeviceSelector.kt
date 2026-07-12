package vn.vietbot.client.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import vn.vietbot.client.data.MicSource
import vn.vietbot.client.data.SpeakerOutput

/**
 * Utility to enumerate system audio devices and map them to app-specific enums.
 * Supports: built-in mic/speaker, Bluetooth SCO/A2DP, USB audio, and HeyCyan glasses (HFP/A2DP).
 */
object AudioDeviceSelector {

    /**
     * Get available microphone sources based on connected hardware.
     * Includes Glasses option only when glasses are connected via BLE (HFP profile).
     */
    fun getAvailableMicSources(context: Context, isGlassesConnected: Boolean): List<MicSource> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val sources = mutableSetOf<MicSource>()

        var hasBluetoothInput = false
        devices.forEach { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> sources.add(MicSource.BUILTIN)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    sources.add(MicSource.BLUETOOTH_SCO)
                    hasBluetoothInput = true
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> sources.add(MicSource.USB)
                else -> {}
            }
        }

        // If no explicit SCO input device but Bluetooth A2DP output exists,
        // the headset mic may only be available after starting SCO.
        // Add BLUETOOTH_SCO as available so user can select it.
        if (!hasBluetoothInput) {
            val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasBluetoothA2dp = outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            if (hasBluetoothA2dp) {
                sources.add(MicSource.BLUETOOTH_SCO)
            }
        }

        if (isGlassesConnected) sources.add(MicSource.GLASSES)
        return sources.toList().sortedBy { it.ordinal }
    }

    /**
     * Get available speaker outputs based on connected hardware.
     * Includes Glasses option only when glasses are connected (A2DP/HFP).
     */
    fun getAvailableSpeakerOutputs(context: Context, isGlassesConnected: Boolean): List<SpeakerOutput> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = mutableSetOf<SpeakerOutput>()

        var hasBluetoothOutput = false
        devices.forEach { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> outputs.add(SpeakerOutput.BUILTIN_SPEAKER)
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> outputs.add(SpeakerOutput.EARPIECE)
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
                    hasBluetoothOutput = true
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    // Combo headset speaker (HFP mono) - same path as A2DP option
                    outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
                    hasBluetoothOutput = true
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> outputs.add(SpeakerOutput.USB)
                else -> {}
            }
        }

        // If no Bluetooth output device enumerated but Bluetooth adapter is on with paired headset,
        // still add BLUETOOTH_A2DP so user can try selecting it.
        if (!hasBluetoothOutput && am.isBluetoothA2dpOn) {
            outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
        }

        if (isGlassesConnected) outputs.add(SpeakerOutput.GLASSES)
        return outputs.toList().sortedBy { it.ordinal }
    }

    /**
     * Find AudioDeviceInfo for a specific MicSource to pass to AudioRecord.Builder.setPreferredDevice().
     * For Bluetooth SCO, the device may not be enumerated until SCO is started.
     */
    fun findInputDevice(context: Context, source: MicSource): AudioDeviceInfo? {
        if (source == MicSource.GLASSES) return findGlassesInputDevice(context)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val targetType = when (source) {
            MicSource.BUILTIN -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            MicSource.BLUETOOTH_SCO -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            MicSource.USB -> AudioDeviceInfo.TYPE_USB_DEVICE
            else -> return null
        }
        // First check if device already enumerated
        val direct = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == targetType }
        if (direct != null) return direct

        // For Bluetooth SCO: start SCO if not already active, then re-query
        if (source == MicSource.BLUETOOTH_SCO) {
            if (am.isBluetoothScoAvailableOffCall && !am.isBluetoothScoOn) {
                am.startBluetoothSco()
                Log.i("AudioDeviceSelector", "Started Bluetooth SCO to enumerate headset mic")
            }
        }
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == targetType }
    }

    /**
     * Find AudioDeviceInfo for a specific SpeakerOutput to pass to AudioTrack.Builder.setPreferredDevice().
     * For Bluetooth, prefer A2DP (stereo), fall back to SCO (mono headset).
     */
    fun findOutputDevice(context: Context, output: SpeakerOutput): AudioDeviceInfo? {
        if (output == SpeakerOutput.GLASSES) return findGlassesOutputDevice(context)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (output) {
            SpeakerOutput.BUILTIN_SPEAKER ->
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            SpeakerOutput.EARPIECE ->
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            SpeakerOutput.BLUETOOTH_A2DP -> {
                // Prefer A2DP (stereo), fall back to SCO (mono headset profile)
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
            SpeakerOutput.USB ->
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
            else -> null
        }
    }

    /** Find HeyCyan glasses input device (Bluetooth SCO with product name containing "HeyCyan") */
    private fun findGlassesInputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.productName?.contains("HeyCyan", true) == true }
    }

    /** Find HeyCyan glasses output device (Bluetooth A2DP with product name containing "HeyCyan") */
    private fun findGlassesOutputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.productName?.contains("HeyCyan", true) == true }
    }
}