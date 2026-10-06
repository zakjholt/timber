package dev.timber.app.engine.midi

/**
 * Groups MIDI 1.0 + MIDI 2.0 alternate settings of one USB gadget.
 * Display names look like "Yamaha Corporation MODX M#577 MIDI 1.0" / "... MIDI 2.0".
 */
object MidiDeviceFamily {
    fun key(serialNumber: String?, displayName: String, productName: String, deviceId: Int): String {
        if (!serialNumber.isNullOrBlank()) return "serial:$serialNumber"
        val stripped = displayName
            .replace(Regex("""\s*#\d+\s*"""), " ")
            .replace(Regex("""\s*MIDI\s*[12](?:\.0)?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
        val product = productName.trim().lowercase()
        return "name:${stripped.ifBlank { product.ifBlank { "id-$deviceId" } }}"
    }
}
