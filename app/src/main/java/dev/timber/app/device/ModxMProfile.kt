package dev.timber.app.device

/**
 * Yamaha MODX M USB audio/MIDI profile.
 *
 * Spec (44.1 kHz, full interface): 10 outputs / 4 inputs from the keyboard's view.
 * From Timber (USB host): those become 10 inputs (stems from MODX) and 4 outputs
 * (return to MODX Digital In / Main).
 *
 * Android class-compliant hosts often expose only stereo I/O. [resolveLayout] picks
 * the richest layout that fits the negotiated device channel counts.
 */
object ModxMProfile {
    const val VENDOR_ID_YAMAHA: Int = 0x0499
    const val PREFERRED_SAMPLE_RATE: Int = 44_100
    const val DISPLAY_NAME: String = "Yamaha MODX M"

    /**
     * Host-input labels for audio arriving FROM the MODX over USB.
     * Indices 0..1 = Main L/R (system + master FX).
     * Indices 2..9 = Assignable USB 1–8 (parts without sys/master FX).
     */
    val hostInputLabelsFull: List<String> = listOf(
        "Main L",
        "Main R",
        "USB 1",
        "USB 2",
        "USB 3",
        "USB 4",
        "USB 5",
        "USB 6",
        "USB 7",
        "USB 8",
    )

    /**
     * Host-output labels for audio Timber sends TO the MODX.
     * Typically lands as Digital In (stereo) plus optional second stereo return
     * when the OS exposes 4 out channels.
     */
    val hostOutputLabelsFull: List<String> = listOf(
        "Return L (Digital In)",
        "Return R (Digital In)",
        "Return 3",
        "Return 4",
    )

    data class Layout(
        val sampleRate: Int,
        val inputChannelCount: Int,
        val outputChannelCount: Int,
        val inputLabels: List<String>,
        val outputLabels: List<String>,
        val stereoPairs: List<Pair<Int, Int>>,
        val notes: String,
    )

    fun resolveLayout(
        availableInputs: Int,
        availableOutputs: Int,
        sampleRate: Int = PREFERRED_SAMPLE_RATE,
    ): Layout {
        val inCount = availableInputs.coerceAtLeast(0)
        val outCount = availableOutputs.coerceAtLeast(0)

        val inputLabels = when {
            inCount >= 10 -> hostInputLabelsFull
            inCount >= 4 -> listOf("Main L", "Main R", "USB 1", "USB 2").take(inCount)
            inCount >= 2 -> listOf("Main L", "Main R")
            inCount == 1 -> listOf("Main")
            else -> emptyList()
        }

        val outputLabels = when {
            outCount >= 4 -> hostOutputLabelsFull
            outCount >= 2 -> listOf("Return L", "Return R")
            outCount == 1 -> listOf("Return")
            else -> emptyList()
        }

        val pairs = buildList {
            var i = 0
            while (i + 1 < inCount) {
                add(i to (i + 1))
                i += 2
            }
        }

        val notes = when {
            inCount >= 10 && sampleRate == PREFERRED_SAMPLE_RATE ->
                "Full MODX M multichannel layout at 44.1 kHz."
            inCount <= 2 ->
                "Class-compliant stereo fallback. Route MODX Parts to Main L/R " +
                    "(or USB 1/2 if that is what the OS exposes) for recording."
            else ->
                "Partial multichannel layout ($inCount in / $outCount out)."
        }

        return Layout(
            sampleRate = sampleRate,
            inputChannelCount = inCount,
            outputChannelCount = outCount,
            inputLabels = inputLabels,
            outputLabels = outputLabels,
            stereoPairs = pairs,
            notes = notes,
        )
    }

    fun looksLikeModxFamily(productName: String?, vendorId: Int?): Boolean {
        if (vendorId == VENDOR_ID_YAMAHA) return true
        val name = productName?.lowercase() ?: return false
        return name.contains("modx") || name.contains("montage") || name.contains("yamaha")
    }
}
