# RTP-MIDI for Android

`rtpmidi` is a reusable Android library for AppleMIDI sessions on a local network. It publishes
and browses `_apple-midi._udp.`, reserves a dynamic consecutive UDP control/data port pair, and
implements the AppleMIDI invitation, clock synchronization, receiver-feedback, and RTP-MIDI
packet formats.

It does not implement or accept XenSynth's former raw UDP/JSON discovery protocol.

```kotlin
val manager = AppleMidiManager(
    context = applicationContext,
    configuration = AppleMidiConfiguration(serviceName = "My App - Android"),
    listener = object : AppleMidiListener {
        override fun onMidiEvent(event: AppleMidiEvent) {
            // event.bytes is one complete MIDI 1.0 channel message.
            // Do not feed network-origin events back to manager.send().
        }
    },
)

manager.start()
val peers = manager.scan()
manager.setDestinationIds(listOf(peers.first().id))
manager.send(listOf(byteArrayOf(0x90.toByte(), 60, 100)))
```

The public package is `icu.ringona.rtpmidi`. The release AAR is produced by:

```text
./gradlew :rtpmidi:assembleRelease
```

The current baseline carries MIDI 1.0 channel voice messages and can receive and preserve an
opaque recovery journal. It sends packets without a recovery journal; SysEx and MIDI 2.0 UMP are
outside this version's API.

Playback starts with a 20 ms adaptive jitter buffer and stays within an 8-40 ms range. The queue
is bounded to 2,048 events, and packet output coalesces only a 1 ms burst while retaining each
message timestamp as RTP-MIDI delta time. A detected sequence gap releases locally tracked notes
and sustain state so packet loss cannot leave a hanging note.
