package icu.ringona.xensynth.midi

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Sends complete MIDI 1.0 messages as individual UDP datagrams on the local network. */
internal class UdpMidiOutput {
    private val lock = Any()
    private val socket = DatagramSocket()
    private var destination: InetSocketAddress? = null

    fun configure(enabled: Boolean, host: String, port: Int) {
        val configuredDestination = if (enabled && host.isNotBlank() && port in 1..65535) {
            runCatching { InetSocketAddress(InetAddress.getByName(host.trim()), port) }
                .onFailure { error -> Log.w(TAG, "Could not resolve network MIDI host", error) }
                .getOrNull()
        } else {
            null
        }
        synchronized(lock) {
            destination = configuredDestination
        }
    }

    fun send(message: ByteArray) {
        val target = synchronized(lock) { destination } ?: return
        runCatching {
            socket.send(DatagramPacket(message, message.size, target))
        }.onFailure { error ->
            Log.w(TAG, "Could not send network MIDI message", error)
        }
    }

    fun close() {
        synchronized(lock) {
            destination = null
        }
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "XenSynthNetworkMidi"
    }
}
