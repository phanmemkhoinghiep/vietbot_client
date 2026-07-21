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
     * Get available microphone sources.
     *
     * Simplified — only 2 options:
     * - BUILTIN: phone mic (system-routed: BT SCO if headset connected, phone mic otherwise)
     * - BLUETOOTH_SCO: explicit headset/wired microphone pin
     *
     * User testing: BUILTIN selected = OS picks whichever mic is active
     * (BT SCO mic if BT headset connected, glasses mic if glasses connected,
     * phone mic otherwise). BLUETOOTH_SCO forces explicit pinning for cases
     * where OS routes wrong mic.
     *
     * Glasses enum removed; BT routing handled by system for BUILTIN.
     */
    fun getAvailableMicSources(context: Context, isGlassesConnected: Boolean): List<MicSource> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // Check if any external mic source is present (wired headset, BT SCO input, USB input)
        val hasExternalMic = inputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

        // If BT A2DP output exists but no BT SCO input discovered yet,
        // still expose BLUETOOTH_SCO option (system will start SCO on selection).
        val hasBtA2dpOutput = outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

        return buildList {
            add(MicSource.BUILTIN)  // always available (phone mic + OS-routed external)
            if (hasExternalMic || hasBtA2dpOutput) {
                add(MicSource.BLUETOOTH_SCO)
            }
        }
    }

    /**
     * Get available speaker outputs.
     *
     * Only 2 options:
     * - BUILTIN_SPEAKER: phone loudspeaker (always present)
     * - EARPIECE: phone receiver (above screen, for calls)
     *
     * Selecting "BUILTIN_SPEAKER" routes to whatever the system already chose
     * (e.g. BT A2DP if a BT speaker is connected, or phone speaker otherwise).
     * Selecting "EARPIECE" forces the receiver (earpiece).
     *
     * Glasses enum removed; BT A2DP/USB enum removed — system handles routing
     * automatically when BUILTIN_SPEAKER is selected.
     */
    fun getAvailableSpeakerOutputs(context: Context, isGlassesConnected: Boolean): List<SpeakerOutput> {
        // Always include both built-in options
        return listOf(SpeakerOutput.BUILTIN_SPEAKER, SpeakerOutput.EARPIECE)
    }

    /**
     * Find AudioDeviceInfo for a specific MicSource.
     *
     * Simplified:
     * - BUILTIN → TYPE_BUILTIN_MIC explicitly (always exists on phone, ensures audio is captured
     *   even if BT SCO was previously started and routed to wrong input).
     * - BLUETOOTH_SCO → pin to TYPE_BLUETOOTH_SCO or TYPE_WIRED_HEADSET.
     *
     * Per user testing: mic path needs EXPLICIT device pin for both selections to work.
     * Letting OS auto-route causes silent failures (phone mic captures nothing when BT SCO
     * is already started, or vice versa).
     */
    fun findInputDevice(context: Context, source: MicSource): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (source) {
            MicSource.BUILTIN ->
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            MicSource.BLUETOOTH_SCO -> {
                val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                if (device != null) return device
                if (am.isBluetoothScoAvailableOffCall && !am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    Log.i("AudioDeviceSelector", "Started Bluetooth SCO to enumerate headset mic")
                }
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
            }
        }
    }

    /**
     * Find AudioDeviceInfo for a specific SpeakerOutput.
     *
     * - BUILTIN_SPEAKER ("Loa Bluetooth") → pin to BT A2DP if connected, else null (OS default = phone speaker).
     *   MUST explicitly pin to BT A2DP because USAGE_VOICE_COMMUNICATION (used in OpusStreamPlayer)
     *   defaults to earpiece/receiver, never A2DP. Without explicit pin, audio always comes out of
     *   the phone receiver regardless of "Loa Bluetooth" setting.
     * - EARPIECE ("Loa Điện thoại") → pin to TYPE_BUILTIN_EARPIECE (phone receiver, force).
     */
    fun findOutputDevice(context: Context, output: SpeakerOutput): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (output) {
            SpeakerOutput.BUILTIN_SPEAKER -> {
                // Look for BT A2DP first (Bluetooth speaker/headset)
                val a2dp = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (a2dp != null) {
                    Log.i("AudioDeviceSelector", "BUILTIN_SPEAKER: pinning to BT A2DP ${a2dp.productName}")
                    return a2dp
                }
                // No BT connected — return null so OS routes to default (phone speaker)
                null
            }
            SpeakerOutput.EARPIECE ->
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
    }

    /** Find HeyCyan glasses input device (Bluetooth SCO with product name containing "HeyCyan") */
    private fun findGlassesInputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.productName?.contains("HeyCyan", true) == true }
    }
}