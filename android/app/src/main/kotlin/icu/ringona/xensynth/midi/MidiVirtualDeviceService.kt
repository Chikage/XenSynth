package icu.ringona.xensynth.midi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver

/** Android system MIDI service exposing Xen Synth as a software MIDI output. */
class MidiVirtualDeviceService : MidiDeviceService() {
    override fun onCreate() {
        super.onCreate()
        refreshOutputReceivers()
    }

    override fun onDeviceStatusChanged(status: android.media.midi.MidiDeviceStatus) {
        refreshOutputReceivers()
    }

    override fun onClose() {
        MidiOutputRouter.detach()
        super.onClose()
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = emptyArray()

    private fun refreshOutputReceivers() {
        runCatching { MidiOutputRouter.attach(getOutputPortReceivers()) }
            .onFailure { MidiOutputRouter.detach() }
    }
}
