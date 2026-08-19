package icu.ringona.xensynth.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

class MidiDeviceInputManager(
    context: Context,
    private val listener: Listener,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : Closeable {
    interface Listener {
        fun onDeviceConnected(device: MidiInputDevice)
        fun onDeviceDisconnected(device: MidiInputDevice)
        fun onMidiEvent(event: MidiInputEvent)
    }

    private val appContext = context.applicationContext
    private val midiManager = appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val openDevices = ConcurrentHashMap<Int, OpenMidiDevice>()
    /** One selected local source, or no source when MIDI input is unassigned. */
    @Volatile
    private var selectedInputIds = emptySet<String>()
    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            openDevice(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            closeDevice(info)
        }
    }

    val isSupported: Boolean =
        midiManager != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    val connectedDeviceCount: Int
        get() = openDevices.size

    /**
     * Returns the currently discoverable Android MIDI input ports.
     *
     * Android exposes an output port as an input source for this application,
     * therefore only [MidiDeviceInfo.PortInfo.TYPE_OUTPUT] ports are listed.
     * The app's own virtual output is omitted to avoid a feedback loop.
     */
    fun inputDevices(): List<MidiInputSource> {
        val manager = midiManager ?: return emptyList()
        val devices = runCatching { manager.devices }
            .onFailure { error -> Log.w(TAG, "Could not enumerate MIDI input devices", error) }
            .getOrNull()
            ?: return emptyList()
        val selected = selectedInputIds
        return devices.asSequence()
            .filter { info -> !info.isOwnVirtualOutput() }
            .flatMap { info ->
                val baseName = info.displayName()
                val model = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val manufacturer = info.properties
                    .getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val transport = info.transportName()
                info.ports.asSequence()
                    .filter { port -> port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                    .map { port ->
                        val id = inputSourceId(info.id, port.portNumber)
                        MidiInputSource(
                            id = id,
                            deviceId = info.id,
                            portNumber = port.portNumber,
                            name = port.displayName(baseName),
                            model = model,
                            manufacturer = manufacturer,
                            transport = transport,
                            selected = id in selected,
                            connected = openDevices.containsKey(info.id),
                        )
                    }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    /** Restricts local input to one source ID. Empty means no local input. */
    fun setInputDeviceIds(ids: Collection<String>, configured: Boolean = true) {
        val normalized = ids.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it.startsWith(INPUT_ID_PREFIX) }
            .firstOrNull()
            ?.let(::setOf)
            .orEmpty()
        selectedInputIds = normalized
        if (!started) return

        // Rebuild open devices so a changed port selection takes effect
        // immediately. Device callbacks are delivered on the same handler.
        openDevices.keys.toList().forEach { deviceId ->
            midiManager?.devices
                ?.firstOrNull { it.id == deviceId }
                ?.let(::closeDevice)
        }
        midiManager?.devices?.forEach(::openDevice)
    }

    @Volatile
    private var started = false

    fun start() {
        val manager = midiManager ?: return
        if (!isSupported || started) {
            return
        }
        started = true
        manager.registerDeviceCallback(deviceCallback, handler)
        manager.devices.forEach { info ->
            openDevice(info)
        }
    }

    fun stop() {
        val manager = midiManager
        if (manager != null && started) {
            runCatching { manager.unregisterDeviceCallback(deviceCallback) }
        }
        started = false
        openDevices.values.toList().forEach { it.close() }
        openDevices.clear()
    }

    override fun close() {
        stop()
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val manager = midiManager ?: return
        // The app exposes its own virtual output port. Opening that port here
        // would feed every scheduled output event straight back into the
        // Flutter MIDI input stream (note-on -> output -> input -> note-on),
        // causing a feedback loop during score playback. Other virtual MIDI
        // devices remain valid input sources and are intentionally untouched.
        if (!started || info.isOwnVirtualOutput() || !info.hasSelectedOutputPorts() ||
            openDevices.containsKey(info.id)
        ) {
            return
        }
        manager.openDevice(
            info,
            { device ->
                if (device == null || !started) {
                    runCatching { device?.close() }
                    return@openDevice
                }
                val ports = device.outputPortConnections()
                if (ports.isEmpty()) {
                    runCatching { device.close() }
                    return@openDevice
                }
                val openDevice = OpenMidiDevice(device = device, ports = ports)
                val previous = openDevices.put(info.id, openDevice)
                previous?.close()
                listener.onDeviceConnected(info.toMidiInputDevice(ports.size))
            },
            handler
        )
    }

    private fun closeDevice(info: MidiDeviceInfo) {
        val openDevice = openDevices.remove(info.id) ?: return
        val portCount = openDevice.ports.size
        openDevice.close()
        listener.onDeviceDisconnected(info.toMidiInputDevice(portCount))
    }

    private fun MidiDeviceInfo.hasSelectedOutputPorts(): Boolean {
        val selected = selectedInputIds
        return ports.any { port ->
            port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT &&
                inputSourceId(id, port.portNumber) in selected
        }
    }

    private fun MidiDeviceInfo.isOwnVirtualOutput(): Boolean {
        if (type != MidiDeviceInfo.TYPE_VIRTUAL) return false
        val properties = properties
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        return name == OWN_OUTPUT_NAME &&
            (manufacturer == OWN_MANUFACTURER || product == OWN_PRODUCT)
    }

    private fun MidiDevice.outputPortConnections(): List<OpenMidiPort> {
        val opened = mutableListOf<OpenMidiPort>()
        val selected = selectedInputIds
        for (port in info.ports) {
            if (port.type != MidiDeviceInfo.PortInfo.TYPE_OUTPUT ||
                inputSourceId(info.id, port.portNumber) !in selected
            ) {
                continue
            }
            val receiver = createReceiver()
            val outputPort = runCatching { openOutputPort(port.portNumber) }
                .onFailure { error ->
                    Log.w(TAG, "Could not open MIDI output port ${port.portNumber}", error)
                }
                .getOrNull()
                ?: continue
            runCatching {
                outputPort.connect(receiver)
                opened += OpenMidiPort(outputPort, receiver)
            }.onFailure { error ->
                Log.w(TAG, "Could not connect MIDI output port ${port.portNumber}", error)
                runCatching { outputPort.close() }
            }
        }
        return opened
    }

    private fun createReceiver(): MidiReceiver {
        return object : MidiReceiver() {
            private val parser = MidiInputParser { event ->
                handler.post {
                    if (started) {
                        listener.onMidiEvent(event)
                    }
                }
            }

            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                if (started) {
                    parser.send(msg, offset, count)
                }
            }
        }
    }

    private fun MidiDeviceInfo.toMidiInputDevice(portCount: Int): MidiInputDevice {
        val name = displayName()
        return MidiInputDevice(
            id = id,
            name = name,
            portCount = portCount
        )
    }

    private fun MidiDeviceInfo.displayName(): String {
        return properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            ?: "MIDI $id"
    }

    private fun MidiDeviceInfo.transportName(): String = when (type) {
        MidiDeviceInfo.TYPE_USB -> "usb"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "bluetooth"
        MidiDeviceInfo.TYPE_VIRTUAL -> "virtual"
        else -> "system"
    }

    private fun MidiDeviceInfo.PortInfo.displayName(deviceName: String): String {
        val portName = name?.trim()?.takeIf(String::isNotEmpty)
        return if (portName == null || portName.equals(deviceName, ignoreCase = true)) {
            deviceName
        } else {
            "$deviceName - $portName"
        }
    }

    private data class OpenMidiDevice(
        val device: MidiDevice,
        val ports: List<OpenMidiPort>
    ) {
        fun close() {
            ports.forEach { port ->
                runCatching { port.output.disconnect(port.receiver) }
                runCatching { port.output.close() }
            }
            runCatching { device.close() }
        }
    }

    private data class OpenMidiPort(
        val output: MidiOutputPort,
        val receiver: MidiReceiver
    )

    companion object {
        private const val TAG = "MidiDeviceInput"
        const val INPUT_ID_PREFIX = "android-midi-input:"
        private const val OWN_OUTPUT_NAME = "XenSynth MIDI Output"
        private const val OWN_MANUFACTURER = "XenSynth"
        private const val OWN_PRODUCT = "XenSynth"

        fun inputSourceId(deviceId: Int, portNumber: Int): String =
            "$INPUT_ID_PREFIX$deviceId:$portNumber"
    }
}

data class MidiInputDevice(
    val id: Int,
    val name: String,
    val portCount: Int
)

/** A port-level Android MIDI source exposed to the settings UI. */
data class MidiInputSource(
    val id: String,
    val deviceId: Int,
    val portNumber: Int,
    val name: String,
    val model: String?,
    val manufacturer: String?,
    val transport: String,
    val selected: Boolean,
    val connected: Boolean,
)
