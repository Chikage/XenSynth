package icu.ringona.xensynth.midi

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/** Opens selected Android MIDI input ports and exposes them to the common output router. */
@Suppress("DEPRECATION")
internal class MidiOutputDestinationManager(context: Context) : Closeable {
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var selectedIds = emptySet<String>()
    private val openDestinations = mutableMapOf<String, OpenDestination>()
    private val openingIds = mutableSetOf<String>()
    private var closed = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()

        override fun onDeviceRemoved(device: MidiDeviceInfo) = refresh()

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = refresh()
    }

    init {
        midiManager?.registerDeviceCallback(deviceCallback, handler)
    }

    /** Lists USB, Bluetooth, virtual, and other system MIDI output targets. */
    fun destinations(): List<Map<String, Any>> {
        val manager = midiManager ?: return emptyList()
        val devices = runCatching { manager.devices }
            .onFailure { error -> Log.w(TAG, "Could not list MIDI outputs", error) }
            .getOrNull()
            ?: return emptyList()
        return devices
            .asSequence()
            .flatMap { info ->
                info.ports.asSequence()
                    .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                    .map { port -> destinationMap(info, port) }
            }
            .sortedBy { it["name"]?.toString()?.lowercase().orEmpty() }
            .toList()
    }

    /** Legacy bridge API retained for settings migration. */
    fun bluetoothDestinations(): List<Map<String, Any>> {
        return destinations().filter { it["transport"] == "bluetooth" }
    }

    fun selectBluetoothDestinations(ids: Collection<String>) {
        // The method name is retained for the persisted Flutter API, but IDs
        // now cover every Android MIDI destination transport.
        selectedIds = ids.filter { it.isNotBlank() }.toSet()
        refresh()
    }

    private fun refresh() {
        if (closed) return
        val manager = midiManager ?: return
        val devices = runCatching { manager.devices }
            .onFailure { error -> Log.w(TAG, "Could not refresh MIDI outputs", error) }
            .getOrNull()
            ?: return
        val candidates = devices
            .flatMap { info ->
                info.ports
                    .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                    .map { port -> Destination(info, port.portNumber) }
            }
            .associateBy { destinationId(it.info, it.portNumber) }

        openDestinations.keys
            .filter { it !in selectedIds || it !in candidates }
            .toList()
            .forEach(::closeDestination)

        candidates.forEach { (id, destination) ->
            if (id in selectedIds && id !in openDestinations && id !in openingIds) {
                openDestination(id, destination)
            }
        }
        publishReceivers()
    }

    private fun openDestination(id: String, destination: Destination) {
        val manager = midiManager ?: return
        openingIds += id
        val opened = MidiManager.OnDeviceOpenedListener { device ->
            openingIds -= id
            if (device == null || closed || id !in selectedIds || id in openDestinations) {
                runCatching { device?.close() }
                return@OnDeviceOpenedListener
            }
            val port = runCatching { device.openInputPort(destination.portNumber) }
                .onFailure { error -> Log.w(TAG, "Could not open MIDI output", error) }
                .getOrNull()
            if (port == null) {
                runCatching { device.close() }
                return@OnDeviceOpenedListener
            }
            openDestinations[id] = OpenDestination(device, port)
            publishReceivers()
        }
        val bluetoothDevice = destination.info.properties
            .getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE) as? BluetoothDevice
        runCatching {
            if (bluetoothDevice != null) {
                manager.openBluetoothDevice(bluetoothDevice, opened, handler)
            } else {
                manager.openDevice(destination.info, opened, handler)
            }
        }.onFailure { error ->
            openingIds -= id
            Log.w(TAG, "Could not connect MIDI output", error)
        }
    }

    private fun closeDestination(id: String) {
        val destination = openDestinations.remove(id) ?: return
        runCatching { destination.port.close() }
        runCatching { destination.device.close() }
        publishReceivers()
    }

    private fun publishReceivers() {
        MidiOutputRouter.setBluetoothReceivers(
            openDestinations.values.map { it.port }.toTypedArray(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { midiManager?.unregisterDeviceCallback(deviceCallback) }
        openDestinations.keys.toList().forEach(::closeDestination)
        openingIds.clear()
        MidiOutputRouter.setBluetoothReceivers(emptyArray())
    }

    private fun displayName(info: MidiDeviceInfo, portNumber: Int): String {
        val properties = info.properties
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI ${info.id}"
        val portName = info.ports
            .firstOrNull { it.portNumber == portNumber }
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return when {
            portName == null || portName.equals(name, ignoreCase = true) -> name
            else -> "$name - $portName"
        }
    }

    private fun destinationMap(
        info: MidiDeviceInfo,
        port: MidiDeviceInfo.PortInfo,
    ): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "id" to destinationId(info, port.portNumber),
            "name" to displayName(info, port.portNumber),
            "transport" to transportName(info),
            "type" to transportName(info),
            "port" to port.portNumber,
            "selected" to (destinationId(info, port.portNumber) in selectedIds),
            "connected" to (destinationId(info, port.portNumber) in openDestinations),
        )
        info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { map["model"] = it }
        info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { map["manufacturer"] = it }
        return map
    }

    private fun destinationId(info: MidiDeviceInfo, portNumber: Int): String =
        outputDestinationId(info.id, portNumber, info.type)

    private fun transportName(info: MidiDeviceInfo): String = when (info.type) {
        MidiDeviceInfo.TYPE_USB -> "usb"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "bluetooth"
        MidiDeviceInfo.TYPE_VIRTUAL -> "virtual"
        else -> "system"
    }

    private data class Destination(val info: MidiDeviceInfo, val portNumber: Int)

    private data class OpenDestination(val device: MidiDevice, val port: MidiInputPort)

    companion object {
        private const val TAG = "MidiOutputDestinations"
        internal const val OUTPUT_ID_PREFIX = "android-midi-output:"

        internal fun outputDestinationId(
            deviceId: Int,
            portNumber: Int,
            deviceType: Int,
        ): String = if (deviceType == MidiDeviceInfo.TYPE_BLUETOOTH) {
            // Preserve IDs written by previous releases.
            "bluetooth:$deviceId:$portNumber"
        } else {
            "$OUTPUT_ID_PREFIX$deviceId:$portNumber"
        }
    }
}
