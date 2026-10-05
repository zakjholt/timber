package dev.timber.app.device

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.timber.app.device.ModxMProfile.Layout

/** Negotiates the best MODX-oriented layout from currently attached USB audio devices. */
object UsbAudioProbe {
    fun probe(audioManager: AudioManager): Layout {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val usbIn = inputs.filter { isUsb(it) }
        val usbOut = outputs.filter { isUsb(it) }

        val inputChannels = usbIn.maxOfOrNull { maxChannels(it) } ?: 2
        val outputChannels = usbOut.maxOfOrNull { maxChannels(it) } ?: 2

        val product = (usbIn + usbOut)
            .mapNotNull { it.productName?.toString() }
            .firstOrNull { ModxMProfile.looksLikeModxFamily(it, null) }

        val layout = ModxMProfile.resolveLayout(
            availableInputs = inputChannels,
            availableOutputs = outputChannels,
            sampleRate = ModxMProfile.PREFERRED_SAMPLE_RATE,
        )

        return if (product != null) {
            layout.copy(notes = "${layout.notes} Device: $product")
        } else {
            layout
        }
    }

    private fun isUsb(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            ModxMProfile.looksLikeModxFamily(device.productName?.toString(), null)
    }

    private fun maxChannels(device: AudioDeviceInfo): Int {
        return device.channelCounts.maxOrNull() ?: device.channelCount
    }
}
