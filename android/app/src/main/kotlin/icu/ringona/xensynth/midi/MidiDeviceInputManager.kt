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
        if (!started || !info.hasOutputPorts() || openDevices.containsKey(info.id)) {
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

    private fun MidiDeviceInfo.hasOutputPorts(): Boolean {
        return ports.any { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
    }

    private fun MidiDevice.outputPortConnections(): List<OpenMidiPort> {
        val opened = mutableListOf<OpenMidiPort>()
        for (port in info.ports) {
            if (port.type != MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
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
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI ${id}"
        return MidiInputDevice(
            id = id,
            name = name,
            portCount = portCount
        )
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
    }
}

data class MidiInputDevice(
    val id: Int,
    val name: String,
    val portCount: Int
)
