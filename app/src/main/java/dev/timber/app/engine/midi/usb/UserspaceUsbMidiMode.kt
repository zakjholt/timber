package dev.timber.app.engine.midi.usb

/**
 * Gate for the userspace USB-MIDI spike (Path B — MIDI only).
 *
 * - [Off]: HAL only (`android.media.midi`) — default production path.
 * - [On]: Claim USB MIDI Streaming interface(s) via [android.hardware.usb.UsbManager]
 *   and decode CIN packets; HAL Yamaha inputs are not used for record.
 * - [Auto]: Start on HAL; if recording sees zero channel-voice events while a
 *   Yamaha USB device is attached, fall over to userspace claim/read.
 */
enum class UserspaceUsbMidiMode {
    Off,
    On,
    Auto,
    ;

    companion object {
        fun fromStorage(raw: String?): UserspaceUsbMidiMode = when (raw?.lowercase()) {
            "on" -> On
            "auto" -> Auto
            else -> Off
        }
    }

    fun toStorage(): String = name.lowercase()

    val label: String
        get() = when (this) {
            Off -> "HAL"
            On -> "Userspace"
            Auto -> "Auto"
        }
}
