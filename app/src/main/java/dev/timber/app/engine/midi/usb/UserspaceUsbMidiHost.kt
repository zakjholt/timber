package dev.timber.app.engine.midi.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.timber.app.device.ModxMProfile
import dev.timber.app.engine.midi.MidiDebugLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Userspace USB-MIDI 1.0 host: permission → claim MIDI Streaming interface(s) →
 * bulk/interrupt IN read loop → CIN decode → MIDI byte callback.
 *
 * OUT endpoint is retained for later thru/clock stubs ([writeShortMessage]).
 *
 * **Risk:** claiming the MIDI interface with force may conflict with Android's
 * MIDI HAL / system MIDI service. Prefer releasing HAL opens for the same
 * physical device before start, or use when HAL ports are silent.
 */
class UserspaceUsbMidiHost(
    private val appContext: Context,
    private val onMidiBytes: (cable: Int, data: ByteArray, timestampNs: Long) -> Unit,
    private val onStatus: (UserspaceUsbMidiStatus) -> Unit = {},
) {
    data class ClaimedIface(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint?,
    )

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var claimed: List<ClaimedIface> = emptyList()
    private var device: UsbDevice? = null
    private var readerThread: Thread? = null
    private var permissionReceiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val grantedDevice = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            MidiDebugLog.i("USB perm granted=$granted device=${grantedDevice?.deviceName}")
            if (granted && grantedDevice != null && running.get()) {
                openAndRead(grantedDevice)
            } else if (!granted) {
                onStatus(UserspaceUsbMidiStatus.PermissionDenied)
            }
        }
    }

    fun findYamahaMidiDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull { device ->
            ModxMProfile.looksLikeModxFamily(device.productName, device.vendorId) &&
                findMidiInterfaces(device).isNotEmpty()
        }
    }

    fun listMidiCables(device: UsbDevice? = findYamahaMidiDevice()): List<Int> {
        // USB-MIDI virtual cables are signaled in packet headers; expose 0..3 for MODX-like
        // multi-port keyboards until we probe descriptors for jack count.
        val target = device ?: return emptyList()
        val ifaces = findMidiInterfaces(target)
        if (ifaces.isEmpty()) return emptyList()
        return (0 until DEFAULT_CABLE_COUNT).toList()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        MidiDebugLog.forceEnable = true
        registerPermissionReceiver()
        val target = findYamahaMidiDevice()
        if (target == null) {
            MidiDebugLog.w("Userspace USB-MIDI: no Yamaha MIDI Streaming device")
            onStatus(UserspaceUsbMidiStatus.NoDevice)
            running.set(false)
            return
        }
        ensurePermissionAndOpen(target)
    }

    fun stop() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        releaseClaim()
        unregisterPermissionReceiver()
        onStatus(UserspaceUsbMidiStatus.Stopped)
        MidiDebugLog.i("Userspace USB-MIDI stopped")
    }

    fun isRunning(): Boolean = running.get() && connection != null

    fun activeDeviceName(): String? = device?.productName ?: device?.deviceName

    /** Stub for later thru/clock: write one short MIDI message as a CIN packet. */
    fun writeShortMessage(cable: Int, midi: ByteArray): Boolean {
        val conn = connection ?: return false
        val out = claimed.firstOrNull()?.outEndpoint ?: return false
        val packet = UsbMidiCin.encodeShortMessage(cable, midi) ?: return false
        return try {
            val written = conn.bulkTransfer(out, packet, packet.size, WRITE_TIMEOUT_MS)
            written == packet.size
        } catch (t: Throwable) {
            MidiDebugLog.w("USB-MIDI OUT failed", t)
            false
        }
    }

    private fun ensurePermissionAndOpen(target: UsbDevice) {
        val manager = usbManager ?: run {
            onStatus(UserspaceUsbMidiStatus.NoDevice)
            running.set(false)
            return
        }
        if (manager.hasPermission(target)) {
            openAndRead(target)
            return
        }
        MidiDebugLog.i("Requesting USB permission for ${target.deviceName}")
        onStatus(UserspaceUsbMidiStatus.AwaitingPermission)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags,
        )
        manager.requestPermission(target, pi)
    }

    private fun openAndRead(target: UsbDevice) {
        val manager = usbManager ?: return
        if (!running.get()) return
        releaseClaim()
        val midiIfaces = findMidiInterfaces(target)
        if (midiIfaces.isEmpty()) {
            MidiDebugLog.w("No MIDI Streaming interfaces on ${target.deviceName}")
            onStatus(UserspaceUsbMidiStatus.NoMidiInterface)
            running.set(false)
            return
        }
        val conn = manager.openDevice(target)
        if (conn == null) {
            MidiDebugLog.e("openDevice failed for ${target.deviceName}")
            onStatus(UserspaceUsbMidiStatus.OpenFailed)
            running.set(false)
            return
        }
        val claimedList = mutableListOf<ClaimedIface>()
        for (iface in midiIfaces) {
            val claimedOk = conn.claimInterface(iface.usbInterface, /* force */ true)
            MidiDebugLog.i(
                "claimInterface ${iface.usbInterface.id} alt=${iface.usbInterface.alternateSetting} " +
                    "ok=$claimedOk inEp=${iface.inEndpoint.address} " +
                    "outEp=${iface.outEndpoint?.address}",
            )
            if (claimedOk) {
                claimedList += iface
            }
        }
        if (claimedList.isEmpty()) {
            conn.close()
            onStatus(UserspaceUsbMidiStatus.ClaimFailed)
            running.set(false)
            return
        }
        connection = conn
        claimed = claimedList
        device = target
        onStatus(
            UserspaceUsbMidiStatus.Running(
                deviceName = target.productName ?: target.deviceName,
                interfaceCount = claimedList.size,
                cables = listMidiCables(target),
            ),
        )
        startReader(conn, claimedList)
    }

    private fun startReader(conn: UsbDeviceConnection, ifaces: List<ClaimedIface>) {
        readerThread?.interrupt()
        readerThread = thread(name = "timber-usb-midi-in", isDaemon = true) {
            MidiDebugLog.i("USB-MIDI IN reader started (${ifaces.size} iface)")
            val buffer = ByteArray(512)
            while (running.get() && !Thread.currentThread().isInterrupted) {
                var any = false
                for (iface in ifaces) {
                    val n = try {
                        conn.bulkTransfer(iface.inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) continue
                    any = true
                    val now = SystemClock.elapsedRealtimeNanos()
                    val packets = UsbMidiCin.decodeBuffer(buffer, n)
                    for (packet in packets) {
                        // Drop pure realtime (clock/active sense) from record path noise,
                        // but still log occasionally.
                        val status = packet.midi.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        if (status >= 0xF8) {
                            if (status == 0xF8 || status == 0xFE) continue
                        }
                        MidiDebugLog.i(
                            "USB-MIDI CIN cable=${packet.cable} cin=0x${packet.cin.toString(16)} " +
                                "bytes=[${packet.midi.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}]",
                        )
                        onMidiBytes(packet.cable, packet.midi, now)
                    }
                }
                if (!any) {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            MidiDebugLog.i("USB-MIDI IN reader exit")
        }
    }

    private fun releaseClaim() {
        val conn = connection
        connection = null
        val list = claimed
        claimed = emptyList()
        device = null
        if (conn != null) {
            for (iface in list) {
                try {
                    conn.releaseInterface(iface.usbInterface)
                } catch (_: Exception) {
                }
            }
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        permissionReceiverRegistered = true
    }

    private fun unregisterPermissionReceiver() {
        if (!permissionReceiverRegistered) return
        try {
            appContext.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
        }
        permissionReceiverRegistered = false
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.timber.app.USB_MIDI_PERMISSION"
        /** Synthetic MidiEngine device id — avoids collision with MidiManager ids. */
        const val SYNTHETIC_DEVICE_ID: Int = -0x0499
        const val DEFAULT_CABLE_COUNT: Int = 4
        private const val READ_TIMEOUT_MS = 50
        private const val WRITE_TIMEOUT_MS = 100

        /** Audio class (0x01) + MIDI Streaming subclass (0x03). */
        const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO // 1
        const val USB_SUBCLASS_MIDISTREAMING = 3

        fun findMidiInterfaces(device: UsbDevice): List<ClaimedIface> {
            val result = mutableListOf<ClaimedIface>()
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != USB_CLASS_AUDIO) continue
                if (iface.interfaceSubclass != USB_SUBCLASS_MIDISTREAMING) continue
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val isBulkOrInterrupt =
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    if (!isBulkOrInterrupt) continue
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN -> if (inEp == null) inEp = ep
                        UsbConstants.USB_DIR_OUT -> if (outEp == null) outEp = ep
                    }
                }
                if (inEp != null) {
                    result += ClaimedIface(iface, inEp, outEp)
                }
            }
            return result
        }
    }
}

sealed class UserspaceUsbMidiStatus {
    data object Stopped : UserspaceUsbMidiStatus()
    data object NoDevice : UserspaceUsbMidiStatus()
    data object NoMidiInterface : UserspaceUsbMidiStatus()
    data object AwaitingPermission : UserspaceUsbMidiStatus()
    data object PermissionDenied : UserspaceUsbMidiStatus()
    data object OpenFailed : UserspaceUsbMidiStatus()
    data object ClaimFailed : UserspaceUsbMidiStatus()
    data class Running(
        val deviceName: String,
        val interfaceCount: Int,
        val cables: List<Int>,
    ) : UserspaceUsbMidiStatus()
}
