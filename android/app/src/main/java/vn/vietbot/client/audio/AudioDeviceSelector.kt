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
     *
     * Covers:
     * - BUILTIN: phone mic (always present)
     * - BLUETOOTH_SCO: BT headset mic (HFP) AND wired 3.5mm headset mic (TYPE_WIRED_HEADSET)
     * - USB: USB audio device mic
     * - GLASSES: HeyCyan BLE glasses (HFP)
     */
    fun getAvailableMicSources(context: Context, isGlassesConnected: Boolean): List<MicSource> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val sources = mutableSetOf<MicSource>()

        var hasBluetoothInput = false
        var hasWiredHeadsetInput = false
        devices.forEach { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> sources.add(MicSource.BUILTIN)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    sources.add(MicSource.BLUETOOTH_SCO)
                    hasBluetoothInput = true
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    // Wired 3.5mm headset mic — same control path as BT SCO
                    sources.add(MicSource.BLUETOOTH_SCO)
                    hasWiredHeadsetInput = true
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> sources.add(MicSource.USB)
                else -> {}
            }
        }

        // If no explicit headset input but Bluetooth A2DP output exists,
        // the BT headset mic may only become available after starting SCO.
        // Add BLUETOOTH_SCO so user can select it (selection triggers SCO start).
        if (!hasBluetoothInput && !hasWiredHeadsetInput) {
            val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasBluetoothA2dp = outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            if (hasBluetoothA2dp) {
                sources.add(MicSource.BLUETOOTH_SCO)
            }
        }

        Log.i(
            "AudioDeviceSelector",
            "getAvailableMicSources → $sources " +
                "(btInput=$hasBluetoothInput, wiredInput=$hasWiredHeadsetInput)"
        )

        if (isGlassesConnected) sources.add(MicSource.GLASSES)
        return sources.toList().sortedBy { it.ordinal }
    }

    /**
     * Get available speaker outputs based on connected hardware.
     * Includes Glasses option only when glasses are connected (A2DP/HFP).
     *
     * Covers:
     * - BUILTIN_SPEAKER: phone loudspeaker (always present)
     * - EARPIECE: phone receiver (above screen, for calls)
     * - BLUETOOTH_A2DP: BT headset/stereo, BT SCO mono, AND wired 3.5mm headset
     * - USB: USB audio device
     * - GLASSES: HeyCyan BLE glasses (A2DP/HFP)
     */
    fun getAvailableSpeakerOutputs(context: Context, isGlassesConnected: Boolean): List<SpeakerOutput> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = mutableSetOf<SpeakerOutput>()

        var hasBluetoothOutput = false
        var hasWiredHeadsetOutput = false
        devices.forEach { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> outputs.add(SpeakerOutput.BUILTIN_SPEAKER)
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> outputs.add(SpeakerOutput.EARPIECE)
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
                    hasBluetoothOutput = true
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    // BT combo headset speaker (HFP mono) - same path as A2DP option
                    outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
                    hasBluetoothOutput = true
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    // Wired 3.5mm headset speaker — user typically pairs this with the BT SCO
                    // mic option (split mic on phone if headset has separate TRRS mic pin).
                    outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
                    hasWiredHeadsetOutput = true
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> outputs.add(SpeakerOutput.USB)
                else -> {}
            }
        }

        // If no Bluetooth/wired output enumerated but Bluetooth adapter is on with paired headset,
        // still add BLUETOOTH_A2DP so user can try selecting it.
        if (!hasBluetoothOutput && !hasWiredHeadsetOutput && am.isBluetoothA2dpOn) {
            outputs.add(SpeakerOutput.BLUETOOTH_A2DP)
        }

        Log.i(
            "AudioDeviceSelector",
            "getAvailableSpeakerOutputs → $outputs " +
                "(btOut=$hasBluetoothOutput, wiredOut=$hasWiredHeadsetOutput)"
        )

        if (isGlassesConnected) outputs.add(SpeakerOutput.GLASSES)
        return outputs.toList().sortedBy { it.ordinal }
    }

    /**
     * Find AudioDeviceInfo for a specific MicSource to pass to AudioRecord.Builder.setPreferredDevice().
     * For Bluetooth SCO, the device may not be enumerated until SCO is started.
     * For wired headset, TYPE_WIRED_HEADSET is returned.
     */
    fun findInputDevice(context: Context, source: MicSource): AudioDeviceInfo? {
        if (source == MicSource.GLASSES) return findGlassesInputDevice(context)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (source) {
            MicSource.BUILTIN ->
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            MicSource.BLUETOOTH_SCO -> {
                // Check both Bluetooth SCO and Wired Headset (3.5mm TRRS)
                val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                if (device != null) return device
                // For Bluetooth SCO: start SCO if not already active, then re-query
                if (am.isBluetoothScoAvailableOffCall && !am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    Log.i("AudioDeviceSelector", "Started Bluetooth SCO to enumerate headset mic")
                }
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            }
            MicSource.USB ->
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            else -> null
        }
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
                // Priority: A2DP (stereo) → SCO (mono BT) → Wired headset (3.5mm)
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
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